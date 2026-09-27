package dev.claudevr.panel

import android.os.Handler
import android.os.Looper
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import java.util.Base64
import java.util.concurrent.Executors

/**
 * One conversation with Claude. History is append-only (images and full
 * assistant turns included) so prompt caching keeps earlier turns cheap.
 * Claude can call show_canvas to draw in the canvas window. Callbacks arrive
 * on the main thread.
 */
class ClaudeChat {

    private val history = mutableListOf<MessageParam>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var client: AnthropicClient? = null
    private var clientKey = ""

    fun reset() = io.execute { history.clear() }

    fun send(
        settings: Settings,
        text: String,
        jpeg: ByteArray?,
        onDelta: (String) -> Unit,
        onCanvas: (title: String, html: String) -> Unit,
        onDone: (reply: String, error: String?) -> Unit,
    ) {
        val apiKey = settings.apiKey
        val model = settings.model
        val effort = effortOf(settings.effort)
        io.execute {
            val reply = StringBuilder()
            val turnStart = history.size
            var error: String? = null
            var drew = false
            try {
                history.add(
                    MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(buildList {
                            if (jpeg != null) add(imageBlock(jpeg))
                            add(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()))
                        })
                        .build()
                )

                for (round in 1..MAX_ROUNDS) {
                    if (reply.isNotEmpty()) emit(reply, "\n\n", onDelta)
                    val acc = MessageAccumulator.create()
                    clientFor(apiKey).messages().createStreaming(request(model, effort)).use { stream ->
                        stream.stream().forEach { event ->
                            acc.accumulate(event)
                            event.contentBlockDelta().flatMap { it.delta().text() }
                                .ifPresent { emit(reply, it.text(), onDelta) }
                        }
                    }
                    val message = acc.message()
                    history.add(message.toParam())

                    val stop = message.stopReason().orElse(null)
                    val toolUses = message.content().mapNotNull { it.toolUse().orElse(null) }
                    if (stop == StopReason.REFUSAL) {
                        error = "Claude declined to answer that."
                        break
                    }
                    if (stop == StopReason.MAX_TOKENS) {
                        error = if (toolUses.isEmpty()) null else "The drawing was too large and got cut off."
                        break
                    }
                    if (stop != StopReason.TOOL_USE || toolUses.isEmpty()) break

                    history.add(
                        MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(toolUses.map { runTool(it, onCanvas).also { drew = true } })
                            .build()
                    )
                    if (round == MAX_ROUNDS) error = "Stopped after $MAX_ROUNDS drawing rounds."
                }
                if (error == null && reply.isEmpty() && !drew) {
                    error = "Empty reply."
                }
            } catch (e: AnthropicServiceException) {
                error = when (e.statusCode()) {
                    401 -> "API key rejected. Check it in Settings."
                    429 -> "Rate limited. Wait a moment and try again."
                    else -> "API error ${e.statusCode()}: ${e.message}"
                }
            } catch (e: Exception) {
                error = "Couldn't reach Claude: ${e.message ?: e.javaClass.simpleName}"
            }
            // A failed turn is dropped whole so history stays a valid, append-only transcript.
            if (error != null) history.subList(turnStart, history.size).clear()
            val finalError = error
            main.post { onDone(reply.toString(), finalError) }
        }
    }

    private fun request(model: String, effort: OutputConfig.Effort) = MessageCreateParams.builder()
        .model(model)
        .maxTokens(32000L)
        .system(SYSTEM_PROMPT)
        .addTool(CANVAS_TOOL)
        .outputConfig(OutputConfig.builder().effort(effort).build())
        .cacheControl(CacheControlEphemeral.builder().build())
        // Server-side refusal fallback: a declined request is retried on a suitable model.
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .messages(history.toList())
        .build()

    private fun emit(reply: StringBuilder, text: String, onDelta: (String) -> Unit) {
        reply.append(text)
        main.post { onDelta(text) }
    }

    /** Validates the (eagerly streamed, so unvalidated) input before showing it. */
    private fun runTool(use: ToolUseBlock, onCanvas: (String, String) -> Unit): ContentBlockParam {
        fun result(text: String, isError: Boolean) = ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder().toolUseId(use.id()).content(text).isError(isError).build()
        )
        if (use.name() != CANVAS_TOOL_NAME) return result("Unknown tool ${use.name()}.", true)
        val input = try {
            use._input().convert(Map::class.java)
        } catch (e: Exception) {
            null
        }
        val html = input?.get("html") as? String
        val title = input?.get("title") as? String ?: "Canvas"
        if (html.isNullOrBlank() || !html.contains("<")) {
            return result("Invalid input: 'html' must be a non-empty HTML document. Try again.", true)
        }
        main.post { onCanvas(title, html) }
        return result("Shown in the canvas window.", false)
    }

    private fun clientFor(apiKey: String): AnthropicClient {
        if (client == null || clientKey != apiKey) {
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            clientKey = apiKey
        }
        return client!!
    }

    private fun imageBlock(jpeg: ByteArray): ContentBlockParam =
        ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(Base64.getEncoder().encodeToString(jpeg))
                        .build()
                )
                .build()
        )

    private fun effortOf(name: String) = when (name) {
        "low" -> OutputConfig.Effort.LOW
        "high" -> OutputConfig.Effort.HIGH
        else -> OutputConfig.Effort.MEDIUM
    }

    companion object {
        private const val MAX_ROUNDS = 4
        private const val CANVAS_TOOL_NAME = "show_canvas"

        private val CANVAS_TOOL: Tool = Tool.builder()
            .name(CANVAS_TOOL_NAME)
            .description(
                """
                Show a visual in the user's canvas window: a separate floating panel they can place next to the chat.
                Use it whenever a sketch, diagram, circuit schematic, chart, formula, layout or small interactive demo
                would explain something better than words, or when the user asks you to draw or show something.
                Provide one complete, self-contained HTML document. Prefer inline SVG for drawings and circuits.
                Libraries may be loaded from https://cdn.jsdelivr.net/npm/ (e.g. mermaid, katex, chart.js).
                The window is roughly 800x700 CSS pixels; use a dark background with light lines and text, and make
                everything fit without scrolling where possible. Each call replaces what the canvas showed before.
                In your chat reply, refer to the drawing briefly rather than repeating it in words.
                """.trimIndent()
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty(
                                "title",
                                JsonValue.from(mapOf("type" to "string", "description" to "Short caption for the window."))
                            )
                            .putAdditionalProperty(
                                "html",
                                JsonValue.from(mapOf("type" to "string", "description" to "Complete HTML document to render."))
                            )
                            .build()
                    )
                    .required(listOf("title", "html"))
                    .build()
            )
            .eagerInputStreaming(true)
            .build()

        private val SYSTEM_PROMPT = """
            You are Claude, running as a floating panel inside the user's Meta Quest 3S headset.
            When a message includes an image, it is a snapshot of exactly what the user was seeing when they sent it:
            either the passthrough view of their real surroundings or the VR app they are in. This panel may appear
            in the snapshot too; ignore it unless asked about it.
            Replies are read on a small panel and may be spoken aloud, so keep them short and conversational.
            Use plain sentences; avoid tables, headings and heavy markdown.
            You also have a canvas window (the show_canvas tool) for anything visual: sketches, circuits, diagrams,
            charts, maths, worked layouts. Reach for it whenever a picture would help more than words.
        """.trimIndent()
    }
}
