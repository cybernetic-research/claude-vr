package dev.claudevr.panel

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Claude's canvas: a second, separately movable panel (own task) that renders
 * the HTML Claude passes to show_canvas. The page is sandboxed: no bridge to
 * the app, no file access, and links open in the Quest browser instead.
 */
class CanvasActivity : Activity() {

    private lateinit var title: TextView
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (10 * resources.displayMetrics.density).toInt()

        title = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#161616"))
            settings.javaScriptEnabled = true // needed for mermaid, katex, charts
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {
                    }
                    return true
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        current = this
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render()
    }

    override fun onDestroy() {
        if (current === this) current = null
        web.destroy()
        super.onDestroy()
    }

    private fun render() {
        val (t, html) = content ?: ("Canvas" to EMPTY)
        title.text = t
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    companion object {
        /** Latest drawing. Held here rather than in the Intent, which caps payload size. */
        private var content: Pair<String, String>? = null
        private var current: CanvasActivity? = null

        /** Shows (or reopens) the canvas window with the given drawing. */
        fun show(context: Context, title: String, html: String) {
            content = title to html
            open(context) // an open canvas re-renders via onNewIntent
        }

        /** Closes the canvas window if it's open (called when the chat panel closes). */
        fun close() {
            current?.finishAndRemoveTask()
        }

        fun open(context: Context) {
            // Separate task affinity (see manifest) makes Horizon OS give it its own panel.
            context.startActivity(
                Intent(context, CanvasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        private const val EMPTY = """<html><body style="background:#161616;color:#888;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:90vh">
            Ask Claude to sketch or show something.</body></html>"""
    }
}
