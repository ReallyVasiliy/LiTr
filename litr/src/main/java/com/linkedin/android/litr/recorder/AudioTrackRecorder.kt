package com.linkedin.android.litr.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import com.linkedin.android.litr.codec.Encoder
import com.linkedin.android.litr.codec.Frame
import com.linkedin.android.litr.exception.TrackTranscoderException
import com.linkedin.android.litr.io.MediaTarget
import com.linkedin.android.litr.recorder.readers.ByteBufferTrackReader
import com.linkedin.android.litr.render.Renderer
import com.linkedin.android.litr.transcoder.TrackTranscoder.*
import java.util.concurrent.TimeUnit


@RestrictTo(RestrictTo.Scope.LIBRARY)
class AudioTrackRecorder internal constructor(
    val reader: ByteBufferTrackReader,
    val mediaTarget: MediaTarget,
    private var targetTrack: Int,
    private var targetFormat: MediaFormat,
    val renderer: Renderer,
    val encoder: Encoder,
): MediaRecorder {
    private var targetTrackAdded: Boolean = false


    @VisibleForTesting
    var lastEncodeFrameResult: Int


    @Throws(TrackTranscoderException::class)
    private fun initCodecs() {
        encoder.init(targetFormat)
        renderer.init(null, null, targetFormat)
    }

    @Throws(TrackTranscoderException::class)
    override fun start() {
        encoder.start()
        reader.start()
    }

    @Throws(TrackTranscoderException::class)
    override fun processNextFrame(): Int {
        if (!encoder.isRunning) {
            // can't do any work
            return ERROR_TRANSCODER_NOT_RUNNING
        }
        var result: Int = RESULT_FRAME_PROCESSED

        val frame = reader.readNextFrame()
        renderer.renderFrame(frame, TimeUnit.MICROSECONDS.toNanos(frame.bufferInfo.presentationTimeUs))

        // get the encoded frame and write it into the target file
        if (lastEncodeFrameResult != RESULT_EOS_REACHED) {
            lastEncodeFrameResult = drainEncoder()
        }
        if (lastEncodeFrameResult == RESULT_OUTPUT_MEDIA_FORMAT_CHANGED) {
            result = RESULT_OUTPUT_MEDIA_FORMAT_CHANGED
        }
        if (lastEncodeFrameResult == RESULT_EOS_REACHED) {
            result = RESULT_EOS_REACHED
        }
        return result
    }

    override fun stop() {
        encoder.stop()
        encoder.release()
        reader.stop()
    }


    @Throws(TrackTranscoderException::class)
    private fun drainEncoder(): Int {
        var encodeFrameResult: Int = RESULT_FRAME_PROCESSED

        while (true) {
            val tag: Int = encoder.dequeueOutputFrame(0)
            if (tag >= 0) {
                val frame: Frame = encoder.getOutputFrame(tag)
                    ?: throw TrackTranscoderException(TrackTranscoderException.Error.NO_FRAME_AVAILABLE)
                if (frame.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "Encoder produced EoS, we are done")
                    encodeFrameResult = RESULT_EOS_REACHED
                } else if (frame.bufferInfo.size > 0
                    && frame.bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    mediaTarget.writeSampleData(targetTrack, frame.buffer!!, frame.bufferInfo)
                }
                encoder.releaseOutputFrame(tag)
                break
            } else {
                when (tag) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        break
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputMediaFormat: MediaFormat = encoder.outputFormat
                        if (!targetTrackAdded) {
                            targetFormat = outputMediaFormat
                            targetTrack = mediaTarget.addTrack(outputMediaFormat, targetTrack)
                            targetTrackAdded = true
                        }
                        encodeFrameResult = RESULT_OUTPUT_MEDIA_FORMAT_CHANGED
                        Log.d(
                            TAG,
                            "Encoder output format received $outputMediaFormat"
                        )
                    }
                    else -> {
                        Log.e(
                            TAG,
                            "Unhandled value $tag when receiving encoded output frame"
                        )
                    }
                }
            }
        }
        return encodeFrameResult
    }

    companion object {
        private val TAG = AudioTrackRecorder::class.java.simpleName
    }

    init {
        lastEncodeFrameResult = RESULT_FRAME_PROCESSED
        initCodecs()
    }
}
