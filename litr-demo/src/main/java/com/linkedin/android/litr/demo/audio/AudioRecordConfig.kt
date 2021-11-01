package com.linkedin.android.litr.demo.audio

import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.linkedin.android.litr.demo.data.AudioTrackFormat
import java.lang.RuntimeException

data class AudioRecordConfig(
    val trackFormat: AudioTrackFormat,
    val encodingFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val framesPerBuffer: Int = 50,
    val sources: List<Int> = listOf(android.media.MediaRecorder.AudioSource.CAMCORDER)
) {

    val samplingRate = trackFormat.samplingRate
    val channelConfig: Int
        get() = when (trackFormat.channelCount) {
            1 -> AudioFormat.CHANNEL_IN_MONO
            2 -> AudioFormat.CHANNEL_IN_STEREO
            else -> throw RuntimeException("Invalid channel count in audio config")
        }

    val samplesPerFrame = 2048 * trackFormat.channelCount

    val mediaFormat by lazy {
        MediaFormat.createAudioFormat(
            trackFormat.mimeType,
            trackFormat.samplingRate,
            trackFormat.channelCount
        ).apply {
            setInteger(MediaFormat.KEY_CHANNEL_MASK, channelConfig)
            setInteger(MediaFormat.KEY_BIT_RATE, trackFormat.bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, trackFormat.channelCount)
        }
    }

    companion object {
        fun createDefault(index: Int) = AudioRecordConfig(
            trackFormat = AudioTrackFormat(index, "audio/mp4a-latm").apply {
                channelCount = 1
                samplingRate = 44100
                bitrate = 128000
            }
        )
    }
}