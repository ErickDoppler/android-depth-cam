package com.example.depthcam

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Choreographer
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the composited depth view into an MP4 in DCIM/DEPTHCAM. Frames are
 * drawn by the provided callback onto the MediaRecorder input surface on
 * every other Choreographer frame (~30 fps).
 */
class DepthRecorder(private val context: Context) {

    var isRecording = false
        private set

    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var name = ""
    private var frameCount = 0
    private var drawFrame: ((Canvas) -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRecording) return
            val s = surface
            if (s != null && s.isValid && frameCount++ % 2 == 0) {
                try {
                    val canvas = s.lockHardwareCanvas()
                    try {
                        drawFrame?.invoke(canvas)
                    } finally {
                        s.unlockCanvasAndPost(canvas)
                    }
                } catch (_: Exception) {
                    // Skip the frame; the encoder keeps the previous one.
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Starts recording; returns true on success. [orientationHint] is the
     *  container rotation (0/90/180/270) players apply on playback. */
    fun start(
        width: Int, height: Int, bitRate: Int,
        withAudio: Boolean, orientationHint: Int, draw: (Canvas) -> Unit
    ): Boolean {
        if (isRecording) return false
        name = "DEPTHCAM_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/DEPTHCAM")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        try {
            val resolver = context.contentResolver
            val u = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            uri = u
            val fd = resolver.openFileDescriptor(u, "rw")
            if (fd == null) {
                cleanup(failed = true)
                return false
            }
            pfd = fd

            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(fd.fileDescriptor)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(width, height)
            mr.setVideoFrameRate(30)
            mr.setVideoEncodingBitRate(bitRate)
            mr.setOrientationHint(((orientationHint % 360) + 360) % 360)
            if (withAudio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(44100)
                mr.setAudioEncodingBitRate(128_000)
                mr.setAudioChannels(1)
            }
            mr.prepare()
            surface = mr.surface
            mr.start()
            recorder = mr

            drawFrame = draw
            frameCount = 0
            isRecording = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
            return true
        } catch (_: Exception) {
            cleanup(failed = true)
            return false
        }
    }

    /** Stops and finalizes the recording; returns the file name, or null. */
    fun stop(): String? {
        if (!isRecording) return null
        isRecording = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val saved = try {
            recorder?.stop()
            true
        } catch (_: Exception) {
            false // stopped too early / no frames
        }
        cleanup(failed = !saved)
        return if (saved) name else null
    }

    private fun cleanup(failed: Boolean) {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        surface?.release()
        surface = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        drawFrame = null

        val u = uri
        uri = null
        if (u != null) {
            val resolver = context.contentResolver
            if (failed) {
                try {
                    resolver.delete(u, null, null)
                } catch (_: Exception) {
                }
            } else {
                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(u, v, null, null)
                } catch (_: Exception) {
                }
            }
        }
    }
}
