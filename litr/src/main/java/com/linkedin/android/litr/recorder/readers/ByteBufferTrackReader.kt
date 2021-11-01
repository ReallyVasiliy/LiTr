package com.linkedin.android.litr.recorder.readers

import com.linkedin.android.litr.codec.Frame

// TODO: Define and provide implementation
interface ByteBufferTrackReader : MediaTrackReader {
    fun readNextFrame(): Frame
}