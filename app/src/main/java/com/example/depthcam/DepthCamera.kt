package com.example.depthcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * Close-to-far colorization gradients for the depth camera. Every LUT has
 * 256 entries: index 0 = closest, 255 = farthest.
 */
object DepthPalette {

    val NAMES = listOf(
        "RAINBOW RED CLOSE",
        "RAINBOW VIOLET CLOSE",
        "WHITE-RED-BLACK",
        "GREEN-BLACK",
        "WHITE-RED-YELLOW-BLUE-BLACK",
        "B/W WHITE CLOSE",
        "B/W BLACK CLOSE",
        "16 COLORS",
        "RED-WHITE-RED-DARKRED",
        "GREEN-WHITE-GREEN-DARKGREEN"
    )

    val LUTS: List<IntArray> by lazy {
        listOf(
            hueLut(0f, 270f),   // red close -> violet far
            hueLut(270f, 0f),   // violet close -> red far
            stopsLut(intArrayOf(Color.WHITE, Color.RED, Color.BLACK)),
            stopsLut(intArrayOf(Color.rgb(0, 255, 70), Color.rgb(0, 140, 30), Color.BLACK)),
            stopsLut(
                intArrayOf(
                    Color.WHITE, Color.RED, Color.YELLOW,
                    Color.BLUE, Color.rgb(0, 0, 120), Color.BLACK
                )
            ),
            stopsLut(intArrayOf(Color.WHITE, Color.BLACK)),
            stopsLut(intArrayOf(Color.BLACK, Color.WHITE)),
            steps16(),
            stopsLut(intArrayOf(Color.RED, Color.WHITE, Color.RED, Color.rgb(80, 0, 0))),
            stopsLut(
                intArrayOf(
                    Color.rgb(0, 255, 0), Color.WHITE,
                    Color.rgb(0, 255, 0), Color.rgb(0, 80, 0)
                )
            )
        )
    }

    private fun hueLut(fromHue: Float, toHue: Float) = IntArray(256) { i ->
        Color.HSVToColor(floatArrayOf(fromHue + (toHue - fromHue) * i / 255f, 1f, 1f))
    }

    /** Linear interpolation through evenly spaced color stops. */
    private fun stopsLut(stops: IntArray) = IntArray(256) { i ->
        val pos = i / 255f * (stops.size - 1)
        val a = pos.toInt().coerceAtMost(stops.size - 2)
        val f = pos - a
        lerp(stops[a], stops[a + 1], f)
    }

    private fun lerp(c1: Int, c2: Int, f: Float): Int = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
    )

    /** The standard 16-color palette, ordered bright (close) to dark (far). */
    private fun steps16(): IntArray {
        val colors = intArrayOf(
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            Color.MAGENTA,
            Color.RED,
            Color.rgb(192, 192, 192), // silver
            Color.rgb(0, 128, 128),   // teal
            Color.rgb(128, 128, 0),   // olive
            Color.rgb(0, 128, 0),     // green (dark)
            Color.rgb(128, 0, 128),   // purple
            Color.BLUE,
            Color.rgb(128, 0, 0),     // maroon
            Color.rgb(128, 128, 128), // grey
            Color.rgb(0, 0, 128),     // navy
            Color.BLACK
        )
        return IntArray(256) { colors[(it * colors.size / 256).coerceIn(0, colors.size - 1)] }
    }
}

/**
 * One usable DEPTH16 stream. [cameraId] is the camera to open; when the
 * depth sensor is a physical sub-camera that cannot be opened on its own
 * (logical multi-camera), [physicalId] routes the stream to it. [listed]
 * is false for cameras found by probing ids absent from the public list.
 */
data class DepthSource(
    val cameraId: String,
    val physicalId: String? = null,
    val listed: Boolean = true
) {
    val label: String
        get() = when {
            physicalId != null -> "ID $cameraId/$physicalId (PHYSICAL)"
            !listed -> "ID $cameraId (HIDDEN)"
            else -> "ID $cameraId"
        }
}

/**
 * Direct Camera2 access to a DEPTH16 (time-of-flight / IR stereo) camera —
 * CameraX hides depth-only cameras, so this runs beside it. DEPTH16 frames
 * are colorized through the selected close-to-far gradient into
 * [latestBitmap].
 *
 * Detection is deliberately permissive to cover OEM quirks:
 *  - Samsung DepthVision (S10 5G/Note10+/S20+/Ultra), Pixel 4 uDepth, LG,
 *    OPPO R17/RX17 Pro, Zebra: DEPTH16 on a listed camera id.
 *  - Newer One UI / Huawei (P30 Pro, Mate 30 Pro) / OPPO / OnePlus builds
 *    hide the ToF module: either an unlisted numeric id (probed 0..79) or a
 *    physical sub-camera of a listed logical camera.
 *  - Some builds report DEPTH16 output sizes without the DEPTH_OUTPUT
 *    capability flag, so the format alone qualifies a camera.
 */
class DepthCamera(private val context: Context) {

    private companion object {
        // Smallest colorization span; keeps flat scenes from flickering.
        const val MIN_SPAN_MM = 150f
        // Smoothing factor for the adaptive near/far range.
        const val RANGE_ALPHA = 0.25f
    }

    @Volatile var lut: IntArray = DepthPalette.LUTS[0]

    @Volatile var latestBitmap: Bitmap? = null
        private set

    /** Invoked on the main thread after every colorized frame. */
    var onFrame: (() -> Unit)? = null

    /** Invoked on the main thread when a [start] attempt fails (open error,
     *  session error, no sizes) — the caller can try the next source. Not
     *  invoked by [stop]. */
    var onUnavailable: (() -> Unit)? = null

    private val mainHandler = Handler(context.mainLooper)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var pixels = IntArray(0)

    val isRunning: Boolean get() = device != null || opening

    @Volatile private var opening = false

    private val manager: CameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun characteristics(id: String): CameraCharacteristics? = try {
        manager.getCameraCharacteristics(id)
    } catch (_: Throwable) {
        null // unknown/forbidden id; OEMs throw various things here
    }

    /** DEPTH16 output sizes of a camera id, or null when it has none. */
    private fun depth16Sizes(id: String) =
        characteristics(id)?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.DEPTH16)?.takeIf { it.isNotEmpty() }

    /**
     * Every depth stream this device offers, in preference order: listed
     * cameras first, then physical sub-cameras, then probed hidden ids.
     * Safe to call from any thread; never throws. May take a moment on
     * devices with slow characteristics queries — call off the main thread.
     */
    fun findDepthSources(): List<DepthSource> {
        val sources = mutableListOf<DepthSource>()
        val covered = mutableSetOf<String>()
        val listed = try {
            manager.cameraIdList.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        // Pass 1: publicly listed depth cameras.
        for (id in listed) {
            if (depth16Sizes(id) != null) {
                sources += DepthSource(id)
                covered += id
            }
        }

        // Pass 2: depth as a physical sub-camera of a listed logical camera.
        for (id in listed) {
            val physical = try {
                characteristics(id)?.physicalCameraIds ?: emptySet()
            } catch (_: Throwable) {
                emptySet()
            }
            for (pid in physical) {
                if (pid in covered || depth16Sizes(pid) == null) continue
                covered += pid
                sources += if (pid in listed) {
                    DepthSource(pid)
                } else {
                    DepthSource(id, physicalId = pid)
                }
            }
        }

        // Pass 3: hidden ids absent from the public list (Huawei, newer
        // One UI, OPPO/OnePlus hide aux cameras behind small numeric ids).
        for (n in 0..79) {
            val id = n.toString()
            if (id in listed || id in covered) continue
            if (depth16Sizes(id) != null) {
                sources += DepthSource(id, listed = false)
                covered += id
            }
        }
        return sources
    }

    private fun notifyUnavailable() {
        mainHandler.post { onUnavailable?.invoke() }
    }

    @SuppressLint("MissingPermission")
    fun start(source: DepthSource) {
        if (isRunning) return
        opening = true
        val size = depth16Sizes(source.physicalId ?: source.cameraId)
            ?.maxByOrNull { it.width * it.height }
        if (size == null) {
            opening = false
            notifyUnavailable()
            return
        }
        thread = HandlerThread("depth-cam").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.DEPTH16, 2
        ).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    colorize(img)
                } finally {
                    img.close()
                }
            }, handler)
        }
        try {
            manager.openCamera(source.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    opening = false
                    device = cam
                    createSession(cam, source.physicalId)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    opening = false
                    cam.close()
                    device = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    opening = false
                    cam.close()
                    device = null
                    notifyUnavailable()
                }
            }, handler)
        } catch (_: Throwable) {
            opening = false
            stop()
            notifyUnavailable()
        }
    }

    private fun createSession(cam: CameraDevice, physicalId: String?) {
        val surface = reader?.surface ?: return
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    req.addTarget(surface)
                    s.setRepeatingRequest(req.build(), null, handler)
                } catch (_: Exception) {
                    notifyUnavailable()
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = notifyUnavailable()
        }
        try {
            val output = OutputConfiguration(surface).apply {
                if (physicalId != null) setPhysicalCameraId(physicalId)
            }
            val executor = java.util.concurrent.Executor { r ->
                handler?.post(r) ?: r.run()
            }
            cam.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(output), executor, callback
                )
            )
        } catch (_: Throwable) {
            stop()
            notifyUnavailable()
        }
    }

    fun stop() {
        opening = false
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // Adaptive colorization range, smoothed across frames: the gradient is
    // stretched over the depths ACTUALLY present in the scene, so every
    // palette shades gradually instead of clumping at one end.
    private var rangeMin = 0f
    private var rangeMax = 0f
    private var rangeInit = false

    private fun colorize(img: Image) {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val rowShorts = plane.rowStride / 2
        val buf = plane.buffer.asShortBuffer()
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val lut = this.lut

        // Pass 1: the frame's real depth extent (0 = no reading, skipped).
        var lo = Int.MAX_VALUE
        var hi = 0
        for (y in 0 until h) {
            val base = y * rowShorts
            for (x in 0 until w) {
                val mm = buf.get(base + x).toInt() and 0x1FFF
                if (mm > 0) {
                    if (mm < lo) lo = mm
                    if (mm > hi) hi = mm
                }
            }
        }
        if (hi > 0) {
            if (!rangeInit) {
                rangeMin = lo.toFloat()
                rangeMax = hi.toFloat()
                rangeInit = true
            } else {
                rangeMin += RANGE_ALPHA * (lo - rangeMin)
                rangeMax += RANGE_ALPHA * (hi - rangeMax)
            }
        }
        val near = rangeMin
        val span = kotlin.math.max(rangeMax - rangeMin, MIN_SPAN_MM)
        val scale = 255f / span

        // Pass 2: colorize through the LUT; no reading maps to the far end.
        for (y in 0 until h) {
            val base = y * rowShorts
            val out = y * w
            for (x in 0 until w) {
                val mm = buf.get(base + x).toInt() and 0x1FFF
                pixels[out + x] = if (mm == 0) {
                    lut[255]
                } else {
                    lut[((mm - near) * scale).toInt().coerceIn(0, 255)]
                }
            }
        }
        val bmp = latestBitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        latestBitmap = bmp
        mainHandler.post { onFrame?.invoke() }
    }
}
