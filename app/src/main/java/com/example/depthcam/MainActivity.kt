package com.example.depthcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Standalone depth (time-of-flight) camera viewer: fullscreen colorized
 * depth display with palette selection, photo capture, video recording and
 * a gallery shortcut. Media is saved to DCIM/DEPTHCAM.
 *
 * Controls: on-screen icon buttons; Volume Up takes a photo (double press
 * rotates the view manually and pins it for the session), Volume Down
 * starts/stops recording. The view auto-rotates by gravity until a manual
 * rotation is made.
 *
 * A long display press or holding Volume Down (~1.2 s) opens the SETTINGS
 * page with live H.264 streaming to a relay server (see [VideoStreamer]).
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var root: FrameLayout
    private lateinit var depthView: DepthView
    private lateinit var statusText: TextView
    private lateinit var buttonBar: LinearLayout
    private lateinit var paletteButton: IconButton
    private lateinit var photoButton: IconButton
    private lateinit var recordButton: IconButton
    private lateinit var galleryButton: Button

    private val depthCamera by lazy { DepthCamera(this) }
    private val recorder by lazy { DepthRecorder(this) }
    private val prefs by lazy { getSharedPreferences("depthcam", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val streamState = StreamState()
    private val videoStreamer = VideoStreamer()
    private lateinit var settingsView: SettingsView
    private var settingsOpen = false

    private var paletteIdx = 0

    // Depth sources detected on this device, tried in order; sourceIdx is
    // the one currently open (or being opened).
    private var depthSources: List<DepthSource>? = null
    private var sourceIdx = 0
    private var detecting = false

    // View rotation: gravity-driven until the user rotates manually (double
    // Volume Up), which pins manual control until the app is restarted.
    private var rotDeg = 0
    private var manualRotation = false

    // Volume Up click counting: 1 = photo, 2 = manual rotate.
    private var volUpCount = 0
    private val volUpSettle = Runnable {
        when {
            volUpCount == 1 -> takePhoto()
            volUpCount >= 2 -> manualRotate()
        }
        volUpCount = 0
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startDepth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        paletteIdx = prefs.getInt("palette", 0).coerceIn(0, DepthPalette.LUTS.size - 1)
        applyPalette()

        streamState.streamMode = prefs.getInt("stream_mode", 0).coerceIn(0, 1)
        streamState.streamUrl = prefs.getString("stream_url", "") ?: ""
        streamState.streamKey = prefs.getString("stream_key", "") ?: ""
        streamState.streamToken = prefs.getString("stream_token", "") ?: ""
        if (streamState.streamMode != 0) {
            mainHandler.post { applyStreamMode(streamState.streamMode) }
        }

        depthCamera.onFrame = {
            depthView.frame = depthCamera.latestBitmap
            if (statusText.visibility == View.VISIBLE) statusText.visibility = View.GONE
        }
        depthCamera.onUnavailable = {
            // This source failed to deliver; move on to the next one.
            if (!depthCamera.isRunning) {
                sourceIdx++
                startSource()
            }
        }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filterNot {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        if (needed.isEmpty()) startDepth() else permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startDepth()
        }
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        if (recorder.isRecording) toggleRecording()
        depthCamera.stop()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        videoStreamer.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------ gravity rotation

    override fun onSensorChanged(event: SensorEvent) {
        if (manualRotation) return
        val ax = event.values[0]
        val ay = event.values[1]
        // Ignore near-flat poses: no meaningful screen-plane gravity.
        if (max(abs(ax), abs(ay)) < 4f) return
        // The activity is landscape-locked; content upright at rotation 0
        // means world-up along device +X. The device turned clockwise by 90
        // puts up along +Y, compensated by drawing at 270, and so on.
        val target = if (abs(ax) >= abs(ay)) {
            if (ax >= 0) 0 else 180
        } else {
            if (ay >= 0) 270 else 90
        }
        if (target != rotDeg) applyRotation(target)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyRotation(deg: Int) {
        // The PREVIEW itself is never rotated: the sensor turns with the
        // screen, so the world stays upright in the fixed mapping (exactly
        // like any camera app). Rotation drives the button bar and the
        // orientation baked into saved photos/videos.
        rotDeg = ((deg % 360) + 360) % 360
        positionBar()
    }

    /**
     * Rotates the WHOLE button bar with the interface and moves it to the
     * edge that is physically "down" for the viewer: bottom at 0°, left at
     * 90°, top at 180°, right at 270°. Rotating the bar as one unit keeps
     * the icons upright and their order natural.
     */
    private fun positionBar() {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w == 0f || h == 0f || buttonBar.height == 0) {
            root.post { positionBar() }
            return
        }
        val inset = buttonBar.height / 2f + 12f * resources.displayMetrics.density
        buttonBar.rotation = rotDeg.toFloat()
        when (rotDeg) {
            0 -> {
                buttonBar.translationX = 0f
                buttonBar.translationY = h / 2f - inset
            }
            90 -> {
                buttonBar.translationX = -(w / 2f - inset)
                buttonBar.translationY = 0f
            }
            180 -> {
                buttonBar.translationX = 0f
                buttonBar.translationY = -(h / 2f - inset)
            }
            else -> {
                buttonBar.translationX = w / 2f - inset
                buttonBar.translationY = 0f
            }
        }
    }

    private fun manualRotate() {
        manualRotation = true // gravity rotation stays off until app restart
        applyRotation(rotDeg + 90)
    }

    // ------------------------------------------------------------ volume keys

    // Volume Down: short press (decided on key up) toggles recording; a
    // ~1.2 s hold opens the settings page. volDownHandled marks presses the
    // key-down already consumed (settings opened or closed).
    private var volDownHandled = false
    private val settingsHold = Runnable {
        volDownHandled = true
        openSettings()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) {
                    if (settingsOpen) {
                        closeSettings()
                    } else {
                        volUpCount++
                        mainHandler.removeCallbacks(volUpSettle)
                        mainHandler.postDelayed(volUpSettle, 350L)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) {
                    volDownHandled = false
                    if (settingsOpen) {
                        closeSettings()
                        volDownHandled = true
                    } else {
                        mainHandler.postDelayed(settingsHold, 1200L)
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (settingsOpen) {
                    closeSettings()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> true
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
            mainHandler.removeCallbacks(settingsHold)
            if (!volDownHandled && !settingsOpen) toggleRecording()
            true
        }
        else -> super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- views

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        depthView = DepthView(this)
        root.addView(
            depthView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this).apply {
            text = "STARTING DEPTH CAMERA..."
            setTextColor(Color.rgb(150, 150, 150))
            typeface = Typeface.MONOSPACE
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dp = resources.displayMetrics.density
        buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val bar = buttonBar
        paletteButton = IconButton(
            this, IconButton.KIND_PALETTE, onLongClick = { openPaletteGallery() }
        ) { cyclePalette() }
        photoButton = IconButton(this, IconButton.KIND_PHOTO) { takePhoto() }
        recordButton = IconButton(this, IconButton.KIND_RECORD) { toggleRecording() }
        for (b in listOf(paletteButton, photoButton, recordButton)) {
            bar.addView(
                b,
                LinearLayout.LayoutParams((64 * dp).toInt(), (44 * dp).toInt()).apply {
                    leftMargin = (6 * dp).toInt()
                    rightMargin = (6 * dp).toInt()
                }
            )
        }
        galleryButton = Button(this).apply {
            text = "GALLERY"
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 40, 40, 40))
            setOnClickListener { openGallery() }
        }
        bar.addView(
            galleryButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()
            ).apply {
                leftMargin = (6 * dp).toInt()
                rightMargin = (6 * dp).toInt()
            }
        )
        // Centered in the root; positionBar() rotates and translates it to
        // the edge that is "down" in the current orientation.
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        root.clipChildren = false
        paletteButton.lut = DepthPalette.LUTS[paletteIdx]

        settingsView = SettingsView(this, streamState).apply {
            visibility = View.GONE
            host = settingsHost
        }
        root.addView(
            settingsView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setupGestures()
        root.post { positionBar() }
    }

    /** Long display press (on empty screen area) opens the settings page. */
    private fun setupGestures() {
        var downX = 0f
        var downY = 0f
        val slop = 24f * resources.displayMetrics.density
        val longOpen = Runnable { openSettings() }
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    mainHandler.postDelayed(longOpen, 800L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        mainHandler.removeCallbacks(longOpen)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    mainHandler.removeCallbacks(longOpen)
                }
            }
            true
        }
    }

    // --------------------------------------------------------------- camera

    private fun startDepth() {
        if (depthCamera.isRunning || detecting) return
        val sources = depthSources
        if (sources == null) {
            detecting = true
            statusText.text = "SEARCHING FOR DEPTH CAMERA..."
            statusText.visibility = View.VISIBLE
            // Characteristics queries (incl. the hidden-id probe) can be
            // slow on some OEM builds; detect off the main thread.
            thread {
                val found = depthCamera.findDepthSources()
                runOnUiThread {
                    detecting = false
                    depthSources = found
                    sourceIdx = 0
                    startSource()
                }
            }
        } else {
            startSource()
        }
    }

    /** Opens the current source, or reports exhaustion of all of them. */
    private fun startSource() {
        if (depthCamera.isRunning) return
        val sources = depthSources ?: return
        val src = sources.getOrNull(sourceIdx)
        if (src == null) {
            statusText.text = if (sources.isEmpty()) {
                "NO DEPTH CAMERA ON THIS DEVICE"
            } else {
                "DEPTH CAMERA DETECTED BUT NOT USABLE"
            }
            statusText.visibility = View.VISIBLE
            sourceIdx = 0 // retry from the best source on the next resume
            return
        }
        statusText.text = "STARTING DEPTH CAMERA ${src.label}..."
        statusText.visibility = View.VISIBLE
        depthCamera.start(src)
    }

    // -------------------------------------------------------------- actions

    private fun cyclePalette() {
        paletteIdx = (paletteIdx + 1) % DepthPalette.LUTS.size
        applyPalette()
        prefs.edit().putInt("palette", paletteIdx).apply()
        Toast.makeText(this, DepthPalette.NAMES[paletteIdx], Toast.LENGTH_SHORT).show()
    }

    private fun applyPalette() {
        depthCamera.lut = DepthPalette.LUTS[paletteIdx]
        if (::paletteButton.isInitialized) {
            paletteButton.lut = DepthPalette.LUTS[paletteIdx]
        }
    }

    // ----------------------------------------------------- palette gallery

    private var paletteGallery: View? = null

    /**
     * Fullscreen scrim with a scrollable list of every palette (name over a
     * full gradient strip); tapping a row selects it, tapping the scrim
     * closes the gallery. The panel is rotated to the current view rotation
     * so it reads upright.
     */
    private fun openPaletteGallery() {
        if (paletteGallery != null) return
        val dp = resources.displayMetrics.density
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        }
        for (i in DepthPalette.LUTS.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                if (i == paletteIdx) setBackgroundColor(Color.argb(255, 70, 70, 70))
                setOnClickListener { selectPalette(i) }
            }
            row.addView(
                TextView(this).apply {
                    text = DepthPalette.NAMES[i]
                    setTextColor(Color.WHITE)
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                }
            )
            row.addView(
                PaletteStrip(this, DepthPalette.LUTS[i]),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (22 * dp).toInt()
                ).apply { topMargin = (4 * dp).toInt() }
            )
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val panel = ScrollView(this).apply {
            addView(rows)
            setBackgroundColor(Color.argb(235, 25, 25, 25))
            isClickable = true // keep panel taps from reaching the scrim
            rotation = rotDeg.toFloat()
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setOnClickListener { closePaletteGallery() }
            addView(
                panel,
                FrameLayout.LayoutParams(
                    (300 * dp).toInt(), (400 * dp).toInt(), Gravity.CENTER
                )
            )
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        paletteGallery = scrim
    }

    private fun selectPalette(idx: Int) {
        paletteIdx = idx
        applyPalette()
        prefs.edit().putInt("palette", idx).apply()
        closePaletteGallery()
        Toast.makeText(this, DepthPalette.NAMES[idx], Toast.LENGTH_SHORT).show()
    }

    private fun closePaletteGallery() {
        paletteGallery?.let { root.removeView(it) }
        paletteGallery = null
    }

    /** The raw scene FILL_CENTER on black, content rotated by [contentRot]
     *  degrees so saved media comes out world-upright. */
    private fun composeFrame(canvas: Canvas, w: Float, h: Float, contentRot: Int) {
        canvas.drawColor(Color.BLACK)
        val bmp = depthCamera.latestBitmap ?: return
        val effW = if (contentRot % 180 == 0) w else h
        val effH = if (contentRot % 180 == 0) h else w
        val fill = max(effW / bmp.width, effH / bmp.height)
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(contentRot.toFloat())
        canvas.scale(fill, fill)
        canvas.drawBitmap(
            bmp, null,
            RectF(-bmp.width / 2f, -bmp.height / 2f, bmp.width / 2f, bmp.height / 2f),
            null
        )
        canvas.restore()
    }

    /** The content rotation that makes saved media upright for the pose the
     *  capture was taken in (the raw frame counter-rotates vs the world). */
    private fun captureRotation(): Int = (360 - rotDeg) % 360

    private fun takePhoto() {
        if (depthCamera.latestBitmap == null) {
            Toast.makeText(this, "No depth frame yet", Toast.LENGTH_SHORT).show()
            return
        }
        val rot = captureRotation()
        val baseW = if (depthView.width > 0) depthView.width else 1280
        val baseH = if (depthView.height > 0) depthView.height else 720
        val w = if (rot % 180 == 0) baseW else baseH
        val h = if (rot % 180 == 0) baseH else baseW
        val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        composeFrame(Canvas(shot), w.toFloat(), h.toFloat(), rot)
        thread { savePhoto(shot) }
    }

    private fun savePhoto(bitmap: Bitmap) {
        val name = "DEPTHCAM_" + java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US
        ).format(java.util.Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/DEPTHCAM")
        }
        val ok = try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri != null && contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } != null
        } catch (_: Exception) {
            false
        } finally {
            bitmap.recycle()
        }
        runOnUiThread {
            Toast.makeText(
                this, if (ok) "Photo saved: $name" else "Photo failed", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            val saved = recorder.stop()
            recordButton.recording = false
            Toast.makeText(
                this,
                if (saved != null) "Video saved: $saved" else "Recording failed",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            val withAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            // Frames are stored unrotated; the container orientation hint
            // (captured at start) makes players show the video upright.
            val ok = recorder.start(1920, 1080, 8_000_000, withAudio, captureRotation()) { canvas ->
                composeFrame(canvas, 1920f, 1080f, 0)
            }
            if (ok) {
                recordButton.recording = true
            } else {
                Toast.makeText(this, "Recording failed to start", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ----------------------------------------------------- settings + stream

    private fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        closePaletteGallery()
        settingsView.visibility = View.VISIBLE
        settingsView.invalidate()
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        settingsView.visibility = View.GONE
        saveStreamPrefs()
    }

    private val settingsHost = object : SettingsView.Host {
        override fun setStreamMode(mode: Int) = applyStreamMode(mode)

        override fun editStreamUrl() = showStreamUrlDialog()

        override fun editStreamKey() = showStreamKeyDialog()

        override fun exitRequested() = closeSettings()
    }

    private val streamListener = object : VideoStreamer.Listener {
        override fun onStreamState(urlOk: Boolean, keyOk: Boolean, keyBusy: Boolean) {
            streamState.streamUrlOk = urlOk
            streamState.streamKeyOk = keyOk
            streamState.streamKeyBusy = keyBusy
            settingsView.postInvalidate()
        }

        override fun onStreamToken(token: String) {
            streamState.streamToken = token
            runOnUiThread { saveStreamPrefs() }
        }
    }

    private fun applyStreamMode(mode: Int) {
        streamState.streamMode = mode.coerceIn(0, 1)
        videoStreamer.stop()
        streamState.streamUrlOk = false
        streamState.streamKeyOk = false
        streamState.streamKeyBusy = false
        if (streamState.streamMode == 1 &&
            streamState.streamUrl.isNotEmpty() && streamState.streamKey.isNotEmpty()
        ) {
            videoStreamer.start(
                streamState.streamUrl, streamState.streamKey, streamState.streamToken,
                streamListener
            ) { canvas ->
                // Rotation baked into the frames so the stream is upright.
                composeFrame(
                    canvas, VideoStreamer.WIDTH.toFloat(), VideoStreamer.HEIGHT.toFloat(),
                    captureRotation()
                )
            }
        }
        saveStreamPrefs()
        settingsView.invalidate()
    }

    private fun saveStreamPrefs() {
        prefs.edit()
            .putInt("stream_mode", streamState.streamMode)
            .putString("stream_url", streamState.streamUrl)
            .putString("stream_key", streamState.streamKey)
            .putString("stream_token", streamState.streamToken)
            .apply()
    }

    private fun showStreamUrlDialog() {
        val input = android.widget.EditText(this).apply {
            setText(streamState.streamUrl)
            hint = "192.168.1.10 or host:port"
            isSingleLine = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("STREAM SERVER URL")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val url = input.text.toString().trim()
                if (VideoStreamer.parseUrl(url) == null) {
                    Toast.makeText(this, "Invalid URL (host:port)", Toast.LENGTH_SHORT).show()
                } else {
                    if (url != streamState.streamUrl) streamState.streamToken = ""
                    streamState.streamUrl = url
                    applyStreamMode(streamState.streamMode)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showStreamKeyDialog() {
        val input = android.widget.EditText(this).apply {
            setText(streamState.streamKey)
            hint = "my-stream-1"
            isSingleLine = true
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("STREAM KEY")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val key = input.text.toString().trim()
                if (!VideoStreamer.isValidKey(key)) {
                    Toast.makeText(
                        this, "Invalid key (letters, digits, - and _)", Toast.LENGTH_SHORT
                    ).show()
                } else {
                    if (key != streamState.streamKey) streamState.streamToken = ""
                    streamState.streamKey = key
                    applyStreamMode(streamState.streamMode)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** Opens the default gallery application. */
    private fun openGallery() {
        try {
            startActivity(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            )
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                )
            } catch (_: Exception) {
                Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * Fullscreen depth display: draws the latest colorized frame FILL_CENTER
 * inside a frame rotated by 0/90/180/270 degrees.
 */
class DepthView(context: Context) : View(context) {

    var frame: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = frame ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        // Fixed FILL_CENTER mapping: the sensor rotates with the screen, so
        // the world stays upright without any drawn rotation.
        val fill = max(w / bmp.width, h / bmp.height)
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.scale(fill, fill)
        dst.set(-bmp.width / 2f, -bmp.height / 2f, bmp.width / 2f, bmp.height / 2f)
        canvas.drawBitmap(bmp, null, dst, null)
        canvas.restore()
    }
}

/**
 * Icon buttons: palette (live gradient strip), photo (camera glyph) and
 * record (red circle; red square while recording).
 */
class IconButton(
    context: Context,
    private val kind: Int,
    private val onLongClick: (() -> Unit)? = null,
    private val onClick: () -> Unit
) : View(context) {

    companion object {
        const val KIND_PALETTE = 0
        const val KIND_PHOTO = 1
        const val KIND_RECORD = 2
    }

    var lut: IntArray? = null
        set(value) {
            field = value
            gradient = null
            invalidate()
        }

    var recording = false
        set(value) {
            field = value
            invalidate()
        }

    private var gradient: Shader? = null
    private val dp get() = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 40, 40, 40)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val rect = RectF()

    init {
        setOnClickListener { onClick() }
        onLongClick?.let { handler ->
            setOnLongClickListener {
                handler()
                true
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 8f * dp, 8f * dp, bg)
        val cx = w / 2f
        val cy = h / 2f
        when (kind) {
            KIND_PALETTE -> {
                val colors = lut ?: return
                if (gradient == null) {
                    // A compact LUT sample keeps the shader cheap.
                    val sample = IntArray(24) { colors[it * 255 / 23] }
                    gradient = LinearGradient(
                        8f * dp, 0f, w - 8f * dp, 0f, sample, null, Shader.TileMode.CLAMP
                    )
                }
                fill.shader = gradient
                rect.set(8f * dp, cy - 10f * dp, w - 8f * dp, cy + 10f * dp)
                canvas.drawRoundRect(rect, 4f * dp, 4f * dp, fill)
                fill.shader = null
            }
            KIND_PHOTO -> {
                stroke.strokeWidth = 2f * dp
                // Camera body with a top viewfinder bump and a lens.
                rect.set(cx - 14f * dp, cy - 8f * dp, cx + 14f * dp, cy + 10f * dp)
                canvas.drawRoundRect(rect, 3f * dp, 3f * dp, stroke)
                rect.set(cx - 5f * dp, cy - 12f * dp, cx + 5f * dp, cy - 8f * dp)
                canvas.drawRect(rect, stroke)
                canvas.drawCircle(cx, cy + 1f * dp, 5f * dp, stroke)
            }
            KIND_RECORD -> {
                fill.color = Color.RED
                if (recording) {
                    rect.set(cx - 8f * dp, cy - 8f * dp, cx + 8f * dp, cy + 8f * dp)
                    canvas.drawRect(rect, fill) // stop square
                } else {
                    canvas.drawCircle(cx, cy, 9f * dp, fill) // record circle
                }
            }
        }
    }
}

/** Horizontal strip rendering a full close-to-far LUT gradient. */
class PaletteStrip(context: Context, private val lut: IntArray) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val sample = IntArray(48) { lut[it * 255 / 47] }
        paint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f, sample, null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = 4f * resources.displayMetrics.density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
