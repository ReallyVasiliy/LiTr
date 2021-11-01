package com.linkedin.android.litr.demo.audio

import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.linkedin.android.litr.codec.Frame
import com.linkedin.android.litr.recorder.readers.ByteBufferTrackReader
import java.nio.ByteBuffer

class AudioTrackReader(private val config: AudioRecordConfig): ByteBufferTrackReader {
    private var buffer: ByteBuffer? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false

    override fun start() {

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.samplingRate,
            config.channelConfig,
            config.encodingFormat
        )

        var bufferSize = config.samplesPerFrame * config.framesPerBuffer
        if (bufferSize < minBufferSize) bufferSize =
            (minBufferSize / config.samplesPerFrame + 1) * config.samplesPerFrame * 2


        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.samplingRate,
            config.channelConfig,
            config.encodingFormat,
            bufferSize
        )

        audioRecord?.let {
            if (it.state == AudioRecord.STATE_INITIALIZED) {

                buffer = ByteBuffer.allocateDirect(config.samplesPerFrame)

                it.startRecording()
                isRunning = true
            }
        } ?: run {
            Log.w(TAG, "Unable to start AudioRecord")
        }
    }

    override fun stop() {
        isRunning = false
        audioRecord?.let {
            it.stop()
            it.release()
        }
        audioRecord = null
        buffer = null
    }

    override fun readNextFrame(): Frame {
        val audioRecord = this.audioRecord
        val buffer = this.buffer

        if (isRunning && audioRecord != null && buffer != null) {
            buffer.clear()
            val bytesRead = audioRecord.read(buffer, config.samplesPerFrame)

            if (bytesRead > 0) {
                buffer.position(bytesRead)
                buffer.flip()
                val pts = System.nanoTime() / 1000
                return Frame(0, buffer, null).apply {
                    bufferInfo.set(0, bytesRead, pts, 0)
                }
            }
        } else {
            Log.w(TAG, "Attempting to read frame while not initialized")
        }
        return Frame(-1, null, null)
    }


    companion object {
        private val TAG = AudioTrackReader::class.qualifiedName

    }
}