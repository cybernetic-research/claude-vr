package dev.claudevr.panel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity(), Voice.Listener {

    private lateinit var settings: Settings
    private lateinit var voice: Voice
    private val chat = ClaudeChat()

    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var scroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var includeView: CheckBox
    private lateinit var micButton: Button
    private lateinit var sendButton: Button

    private var busy = false
    private var askedForCapture = false

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        voice = Voice(this).also { it.listener = this }
        setContentView(FrameLayout(this).apply {
            addView(buildUi())
            if (savedInstanceState == null) showSplash(this)
        })

        CaptureService.listener = { running, error ->
            updateCaptureState()
            if (error != null) addNote(error)
            else if (!running) addNote("Screen capture stopped. Claude can't see your view until you restart it.")
        }
        updateCaptureState()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        addNote("Ask anything. With \"Send my view\" on, Claude sees what you see when you send.")
        if (settings.apiKey.isBlank()) showSettings()
    }

    override fun onResume() {
        super.onResume()
        // Ask for capture once per launch; the consent prompt is Horizon OS's own.
        if (!askedForCapture && settings.apiKey.isNotBlank() && CaptureService.instance == null) {
            askedForCapture = true
            requestCapture()
        }
    }

    override fun onDestroy() {
        CaptureService.listener = null
        voice.release()
        super.onDestroy()
    }

    // ---- UI -------------------------------------------------------------

    private fun showSplash(root: FrameLayout) {
        val splash = SplashView(this)
        root.addView(splash)
        splash.animate()
            .alpha(0f)
            .setStartDelay(SPLASH_MS)
            .setDuration(400)
            .withEndAction { root.removeView(splash) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        status = TextView(this).apply { textSize = 14f }
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        captureButton = smallButton("") { toggleCapture() }
        top.addView(captureButton)
        top.addView(smallButton("Canvas") { CanvasActivity.open(this) })
        top.addView(smallButton("New chat") { newChat() })
        top.addView(smallButton("Settings") { showSettings() })
        root.addView(top)

        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll = ScrollView(this).apply { addView(messages) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        includeView = CheckBox(this).apply {
            text = "Send my view"
            isChecked = settings.includeView
            setOnCheckedChangeListener { _, checked -> settings.includeView = checked }
        }
        root.addView(includeView)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Message Claude"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEND || enter) { send(); true } else false
            }
        }
        bottom.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        micButton = Button(this).apply {
            text = "Mic"
            setOnClickListener { toggleMic() }
        }
        bottom.addView(micButton)
        sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { send() }
        }
        bottom.addView(sendButton)
        root.addView(bottom)

        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun bubble(text: String, fromUser: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (fromUser) 0xFF2F4A6D.toInt() else 0xFF2E2E2E.toInt())
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            gravity = if (fromUser) Gravity.END else Gravity.START
            if (fromUser) leftMargin = dp(48) else rightMargin = dp(48)
        }
        messages.addView(tv, lp)
        scrollToEnd()
        return tv
    }

    private fun addThumbnail(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            maxHeight = dp(120)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            gravity = Gravity.END
        }
        messages.addView(iv, lp)
        scrollToEnd()
    }

    private fun addNote(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF9A9A9A.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        messages.addView(tv)
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun setBusy(value: Boolean) {
        busy = value
        sendButton.isEnabled = !value
        sendButton.text = if (value) "…" else "Send"
    }

    // ---- Sending --------------------------------------------------------

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (settings.apiKey.isBlank()) {
            showSettings()
            return
        }
        input.setText("")
        setBusy(true)
        voice.stopSpeaking()
        bubble(text, fromUser = true)

        val capture = CaptureService.instance
        when {
            !includeView.isChecked -> ask(text, null)
            capture == null -> {
                addNote("View not attached: screen capture is off.")
                ask(text, null)
            }
            else -> capture.snapshot { jpeg ->
                if (jpeg != null) addThumbnail(jpeg) else addNote("View not attached: no frame yet.")
                ask(text, jpeg)
            }
        }
    }

    private fun ask(text: String, jpeg: ByteArray?) {
        val reply = bubble("…", fromUser = false)
        var started = false
        chat.send(settings, text, jpeg,
            onDelta = { delta ->
                if (!started) { reply.text = ""; started = true }
                reply.append(delta)
                scrollToEnd()
            },
            onCanvas = { title, html ->
                CanvasActivity.show(this, title, html)
                addNote("Drew on the canvas: $title")
            },
            onDone = { full, error ->
                setBusy(false)
                if (error != null) {
                    if (!started) messages.removeView(reply)
                    addNote(error)
                } else if (settings.speakReplies) {
                    voice.speak(full)
                }
            }
        )
    }

    private fun newChat() {
        chat.reset()
        voice.stopSpeaking()
        messages.removeAllViews()
        addNote("New chat.")
    }

    // ---- Screen capture -------------------------------------------------

    private fun updateCaptureState() {
        val on = CaptureService.instance != null
        status.text = if (on) "● Claude can see your view" else "○ View capture off"
        status.setTextColor(if (on) 0xFF7BD88F.toInt() else 0xFF9A9A9A.toInt())
        captureButton.text = if (on) "Stop view" else "Share view"
    }

    private fun toggleCapture() {
        val svc = CaptureService.instance
        if (svc != null) svc.stopCapture() else requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: ActivityNotFoundException) {
            addNote("This headset doesn't support screen capture.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            addNote("Screen capture declined. Tap \"Share view\" to allow it later.")
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        )
    }

    // ---- Voice ----------------------------------------------------------

    private fun toggleMic() {
        if (voice.listening) {
            voice.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!voice.canListen) {
            addNote("No speech recogniser available on this device.")
            return
        }
        voice.startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleMic()
        else addNote("Microphone permission denied.")
    }

    override fun onPartial(text: String) {
        input.setText(text)
        input.setSelection(text.length)
    }

    override fun onFinal(text: String) {
        input.setText(text)
        send()
    }

    override fun onListeningChanged(listening: Boolean) {
        micButton.text = if (listening) "Stop" else "Mic"
        if (listening) input.hint = "Listening…" else input.hint = "Message Claude"
    }

    override fun onError(message: String) = addNote(message)

    // ---- Settings -------------------------------------------------------

    private fun showSettings() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = form.addView(TextView(this).apply { text = t; setPadding(0, dp(10), 0, 0) })

        label("Anthropic API key")
        val key = EditText(this).apply {
            setText(settings.apiKey)
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        form.addView(key)

        label("Model")
        val model = EditText(this).apply {
            setText(settings.model)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        form.addView(model)

        label("Thinking effort")
        val effort = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Settings.EFFORTS)
            setSelection(Settings.EFFORTS.indexOf(settings.effort).coerceAtLeast(0))
        }
        form.addView(effort)

        val speak = CheckBox(this).apply {
            text = "Speak replies aloud"
            isChecked = settings.speakReplies
        }
        form.addView(speak)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                settings.apiKey = key.text.toString()
                settings.model = model.text.toString()
                settings.effort = effort.selectedItem as String
                settings.speakReplies = speak.isChecked
                if (!askedForCapture && settings.apiKey.isNotBlank() && CaptureService.instance == null) {
                    askedForCapture = true
                    requestCapture()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_NOTIF = 2
        private const val REQ_MIC = 3
        private const val SPLASH_MS = 3000L
    }
}
