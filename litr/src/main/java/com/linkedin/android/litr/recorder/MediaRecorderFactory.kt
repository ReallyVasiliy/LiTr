package com.linkedin.android.litr.recorder

import android.media.MediaFormat
import com.linkedin.android.litr.exception.TrackTranscoderException
import com.linkedin.android.litr.recorder.readers.ByteBufferTrackReader
import com.linkedin.android.litr.recorder.readers.SurfaceTrackReader
import com.linkedin.android.litr.render.GlVideoRenderer
import com.linkedin.android.litr.render.PassthroughSoftwareRenderer


class MediaRecorderFactory {

    @Throws(TrackTranscoderException::class)
    fun create(params: MediaRecordParameters): MediaRecorder {
        val targetTrackMimeType = params.targetFormat.getString(MediaFormat.KEY_MIME) ?: throw TrackTranscoderException(
            TrackTranscoderException.Error.SOURCE_TRACK_MIME_TYPE_NOT_FOUND,
            params.targetFormat,
            null,
            null
        )

        val isVideo = targetTrackMimeType.startsWith("video")
        val isAudio = targetTrackMimeType.startsWith("audio")

        return when {
            isVideo -> {
                SurfaceMediaRecorder(
                    params.reader as? SurfaceTrackReader ?: throw TrackTranscoderException(
                        TrackTranscoderException.Error.READER_NOT_COMPATIBLE,
                        params.targetFormat,
                        null,
                        null
                    ),
                    params.mediaTarget,
                    params.targetTrack,
                    params.targetFormat,
                    (params.renderer as? GlVideoRenderer) ?: throw TrackTranscoderException(
                        TrackTranscoderException.Error.RENDERER_NOT_COMPATIBLE,
                        params.targetFormat,
                        null,
                        null
                    ),
                    params.encoder
                )
            }
            isAudio -> {
                val renderer = params.renderer ?: PassthroughSoftwareRenderer(params.encoder)
                AudioTrackRecorder(
                    params.reader as? ByteBufferTrackReader ?: throw TrackTranscoderException(TrackTranscoderException.Error.READER_NOT_COMPATIBLE, params.targetFormat, null, null),
                    params.mediaTarget,
                    params.targetTrack,
                    params.targetFormat,
                    renderer,
                    params.encoder
                )
            }
            // TODO: Handle audio/other/passthrough reader types, have descriptive exception
            else -> throw TrackTranscoderException(TrackTranscoderException.Error.SOURCE_TRACK_MIME_TYPE_NOT_FOUND, params.targetFormat, null, null)
        }

    }
}