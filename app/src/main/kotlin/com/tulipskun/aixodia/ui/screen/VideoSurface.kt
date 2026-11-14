package com.tulipskun.aixodia.ui.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodes the H.264 stream onto a Surface.
 *
 * The engine sends Annex B access units, which is what MediaCodec reads, so there
 * is no conversion between the wire and the decoder. Frames are fed one at a
 * time on a thread of their own: decoding a 720p frame takes a few milliseconds
 * and doing it on the main thread would stutter the touch handling that has to
 * stay immediate.
 *
 * The decoder is rebuilt on a size change and after a failure, because a
 * decoder that has seen a broken access unit stays broken.
 */
class VideoSurface(
    private val onFirstFrame: () -> Unit,
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var awaitingKeyframe = true
    private var framesDelivered = 0

    /** True once a frame has reached the screen. */
    val hasPicture: Boolean get() = framesDelivered > 0

    fun attach(target: Surface) {
        if (surface === target && codec != null) return
        surface = target
        start()
    }

    private fun start() {
        stop()
        val target = surface ?: return
        if (width <= 0 || height <= 0) return
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            // The bitstream carries its own SPS/PPS before each keyframe, so the
            // decoder must be told not to wait for them out of band.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
        }
        val created = runCatching {
            MediaCodec.createDecoderByType(MIME).also { it.configure(format, target, null, 0) }
        }.getOrElse { error ->
            Log.w(TAG, "no h264 decoder for ${width}x$height: ${error.message}")
            return
        }
        created.start()
        codec = created
        awaitingKeyframe = true
        framesDelivered = 0

        val background = HandlerThread("aixodia-video").also { it.start() }
        thread = background
        handler = Handler(background.looper)
    }

    /**
     * Feeds one access unit.
     *
     * A frame that does not stand on its own is dropped until a keyframe has been
     * seen, because a partly-known picture decodes to garbage that is worse to
     * look at than the last good frame.
     */
    fun submit(accessUnit: ByteArray, keyframe: Boolean) {
        val codec = codec ?: return
        val handler = handler ?: return
        if (awaitingKeyframe && !keyframe) return
        handler.post {
            val decoder = codec
            runCatching {
                val input = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (input < 0) return@runCatching
                val buffer: ByteBuffer = decoder.getInputBuffer(input) ?: return@runCatching
                buffer.clear()
                if (buffer.capacity() < accessUnit.size) {
                    // A frame larger than the decoder was told to expect means
                    // the settings changed; rebuilding is the honest answer.
                    restart()
                    return@runCatching
                }
                buffer.put(accessUnit)
                // A timestamp that never advances makes a decoder drop frames as
                // out of order, so each one is given its own.
                frameTime += FRAME_INTERVAL_US
                decoder.queueInputBuffer(input, 0, accessUnit.size, frameTime, 0)
                awaitingKeyframe = false

                val info = MediaCodec.BufferInfo()
                var output = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                while (output >= 0) {
                    // Rendered by the Surface: `true` puts it on the display.
                    decoder.releaseOutputBuffer(output, true)
                    if (framesDelivered == 0) onFirstFrame()
                    framesDelivered++
                    output = decoder.dequeueOutputBuffer(info, 0)
                }
            }.onFailure { error ->
                Log.w(TAG, "decode failed: ${error.message}")
                restart()
            }
        }
    }

    /** Rebuilds the decoder, which is the only reliable repair after a failure. */
    private fun restart() {
        handler?.post {
            start()
        }
    }

    fun stop() {
        thread?.quitSafely()
        thread = null
        handler = null
        codec?.let { decoder ->
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        codec = null
    }

    fun sizeTo(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        start()
    }

    private var frameTime = 0L

    private companion object {
        const val TAG = "AixodiaVideo"
        const val MIME = "video/avc"
        /** How long to wait for a decoder buffer before giving up on this frame. */
        const val TIMEOUT_US = 10_000L
        /** A nominal frame interval for the decoder's ordering. */
        const val FRAME_INTERVAL_US = 16_666L
    }
}
