package com.example.depthcam

import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Choreographer
import android.view.Surface
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Streams the composited depth scene as live H.264 to a streaming relay
 * server.
 *
 * The scene is drawn by the provided callback onto the encoder input surface
 * on every other Choreographer frame (~30 fps) — the ScreenRecorder pattern.
 * A background connection loop handshakes with the server
 * ("STREAM <key>\n" -> "OK" | "BUSY" | "BAD") and then pushes every encoded
 * frame as: u32be payloadLength, u8 flags, u64be ptsMillis, payload.
 * Flags: bit0 = video keyframe (payload = Annex-B NALs; SPS/PPS is prepended
 * to every keyframe so viewers can join mid-stream), bit1 = audio (payload =
 * one AAC frame in ADTS framing). Microphone sound is streamed whenever the
 * RECORD_AUDIO permission is granted — the AudioRecord simply fails to
 * initialize without it and the stream stays video-only. Lost connections
 * reconnect with backoff; the reported state drives the OK / BUSY markers
 * on the settings page.
 */
class VideoStreamer {

    interface Listener {
        /** State flip; called on a BACKGROUND thread. */
        fun onStreamState(urlOk: Boolean, keyOk: Boolean, keyBusy: Boolean)

        /** Server-issued device validation token — persist it and pass it to
         *  the next [start] so a restarted app can reclaim its own key from
         *  a stale session. Never shown to users. BACKGROUND thread. */
        fun onStreamToken(token: String)
    }

    companion object {
        const val WIDTH = 854
        const val HEIGHT = 480
        private const val BIT_RATE = 1_500_000
        private const val FPS = 30
        private const val AUDIO_RATE = 44100
        private const val AUDIO_BIT_RATE = 64_000
        private const val FLAG_KEYFRAME = 1
        private const val FLAG_AUDIO = 2
        private val KEY_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidKey(key: String): Boolean = KEY_RE.matches(key)

        /** "host[:port]", optionally with an http:// or tcp:// prefix.
         *  No port means 80 — the server's preferred standard port. */
        fun parseUrl(url: String): Pair<String, Int>? {
            var s = url.trim()
            for (p in listOf("http://", "tcp://")) {
                if (s.startsWith(p, ignoreCase = true)) s = s.substring(p.length)
            }
            s = s.trimEnd('/')
            val colon = s.lastIndexOf(':')
            if (colon < 0) return if (s.isNotEmpty()) s to 80 else null
            if (colon == 0) return null
            val host = s.substring(0, colon)
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return host to port
        }
    }

    var isStreaming = false
        private set

    @Volatile private var gen = 0
    @Volatile private var drawFrame: ((Canvas) -> Unit)? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var socketOut: DataOutputStream? = null

    private var frameCount = 0
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isStreaming) return
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

    /** Starts encoding and connecting; call on the main thread.
     *  [token] is the validation token from a previous session ("" = none). */
    fun start(
        url: String, key: String, token: String,
        listener: Listener, draw: (Canvas) -> Unit
    ): Boolean {
        stop()
        val target = parseUrl(url) ?: return false
        if (!isValidKey(key)) return false
        val codec: MediaCodec
        val inSurface: Surface
        try {
            codec = MediaCodec.createEncoderByType("video/avc")
            val fmt = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inSurface = codec.createInputSurface()
            codec.start()
        } catch (_: Exception) {
            return false
        }
        encoder = codec
        surface = inSurface
        drawFrame = draw
        frameCount = 0
        isStreaming = true
        val g = ++gen
        Choreographer.getInstance().postFrameCallback(frameCallback)
        thread(isDaemon = true, name = "stream-drain") { drainLoop(g, codec) }
        thread(isDaemon = true, name = "stream-audio") { audioLoop(g) }
        thread(isDaemon = true, name = "stream-connect") {
            connectLoop(g, target.first, target.second, key, token, listener)
        }
        return true
    }

    fun stop() {
        if (!isStreaming && encoder == null) {
            gen++
            return
        }
        gen++
        isStreaming = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        drawFrame = null
        socketOut = null
        val codec = encoder
        encoder = null
        try {
            surface?.release()
        } catch (_: Exception) {
        }
        surface = null
        // Stopping the codec kicks the drain thread out of its dequeue.
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
    }

    // -------------------------------------------------------------- encoding

    private fun drainLoop(g: Int, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var spsPps: ByteArray? = null
        while (g == gen) {
            val idx = try {
                codec.dequeueOutputBuffer(info, 100_000)
            } catch (_: Exception) {
                break
            }
            if (idx < 0) continue
            try {
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        spsPps = data // SPS+PPS in one Annex-B blob
                    } else {
                        val isKey =
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val cfg = spsPps
                        val payload = if (isKey && cfg != null) cfg + data else data
                        sendFrame(if (isKey) FLAG_KEYFRAME else 0,
                            info.presentationTimeUs / 1000, payload)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            } catch (_: Exception) {
                break
            }
        }
    }

    // ----------------------------------------------------------------- audio

    /**
     * Microphone -> AAC-LC in ADTS framing, one stream frame per AAC frame.
     * Audio pts uses the same System.nanoTime domain the video encoder
     * stamps its surface frames with, so the viewer can align the tracks.
     */
    private fun audioLoop(g: Int) {
        var rec: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, AUDIO_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, 8192)
            )
            // Uninitialized = RECORD_AUDIO not granted: stream video-only.
            if (rec.state != AudioRecord.STATE_INITIALIZED) return
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            val fmt = MediaFormat.createAudioFormat(
                "audio/mp4a-latm", AUDIO_RATE, 1
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            rec.startRecording()
            val pcm = ByteArray(2048)
            val info = MediaCodec.BufferInfo()
            while (g == gen) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n > 0) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)
                        if (ib != null) {
                            ib.clear()
                            ib.put(pcm, 0, n)
                            codec.queueInputBuffer(
                                idx, 0, n, System.nanoTime() / 1000, 0
                            )
                        }
                    }
                }
                while (true) {
                    val oi = codec.dequeueOutputBuffer(info, 0)
                    if (oi < 0) break
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val aac = ByteArray(info.size)
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.get(aac)
                        sendFrame(
                            FLAG_AUDIO, info.presentationTimeUs / 1000,
                            adtsHeader(aac.size) + aac
                        )
                    }
                    codec.releaseOutputBuffer(oi, false)
                }
            }
        } catch (_: Exception) {
            // No microphone / codec trouble: the stream stays video-only.
        } finally {
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            try {
                rec?.release()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }

    /** 7-byte ADTS header: AAC-LC, 44.1 kHz, mono, no CRC. */
    private fun adtsHeader(aacLen: Int): ByteArray {
        val freqIdx = 4 // 44100
        val chan = 1
        val full = aacLen + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((2 - 1) shl 6) or (freqIdx shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (full shr 11)).toByte(),
            ((full shr 3) and 0xFF).toByte(),
            (((full and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    private fun sendFrame(flags: Int, ptsMs: Long, payload: ByteArray) {
        val out = socketOut ?: return
        try {
            synchronized(out) {
                out.writeInt(payload.size)
                out.writeByte(flags)
                out.writeLong(ptsMs)
                out.write(payload)
                out.flush()
            }
        } catch (_: Exception) {
            socketOut = null // the connect loop reconnects
        }
    }

    /** A fresh keyframe right after (re)connecting locks viewers on fast. */
    private fun requestKeyFrame() {
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ connection

    private fun connectLoop(
        g: Int, host: String, port: Int, key: String,
        initialToken: String, listener: Listener
    ) {
        var token = initialToken
        while (g == gen) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 3000)
                listener.onStreamState(urlOk = true, keyOk = false, keyBusy = false)
                val hello = "STREAM $key" +
                    (if (token.isNotEmpty()) " $token" else "") + "\n"
                socket.getOutputStream().write(hello.toByteArray())
                socket.soTimeout = 5000
                val reply = readLine(socket)
                when {
                    reply == "OK" || reply.startsWith("OK ") -> {
                        // The server's device validation token rides on the
                        // OK; presenting it next time reclaims the key from
                        // a stale session of our own.
                        val t = reply.removePrefix("OK").trim()
                        if (t.isNotEmpty() && t != token) {
                            token = t
                            listener.onStreamToken(t)
                        }
                        listener.onStreamState(true, true, false)
                        socket.soTimeout = 0
                        val out = DataOutputStream(socket.getOutputStream())
                        socketOut = out
                        requestKeyFrame()
                        // Park on the input: the server sends nothing after
                        // OK, so read() returning ends the session.
                        try {
                            val ins = socket.getInputStream()
                            while (g == gen && socketOut === out) {
                                if (ins.read() < 0) break
                            }
                        } catch (_: Exception) {
                        }
                        socketOut = null
                        if (g == gen) listener.onStreamState(false, false, false)
                    }
                    reply == "BUSY" -> listener.onStreamState(true, false, true)
                    else -> listener.onStreamState(true, false, false)
                }
            } catch (_: Exception) {
                if (g == gen) listener.onStreamState(false, false, false)
            } finally {
                socketOut = null
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
            if (g != gen) break
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun readLine(socket: Socket): String {
        val sb = StringBuilder()
        val ins = socket.getInputStream()
        while (sb.length < 64) {
            val c = ins.read()
            if (c < 0 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
