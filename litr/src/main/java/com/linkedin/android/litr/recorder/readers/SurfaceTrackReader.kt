package com.linkedin.android.litr.recorder.readers

import android.view.Surface

interface SurfaceTrackReader : MediaTrackReader {
    fun drawFrame(surface: Surface, presentationTimeNs: Long)
}