package com.sowz.aestudio.core

/** JNI entry point for the pinned read-only AEP parser. */
class AepNativeReader {
    external fun inspectAep(bytes: ByteArray): String

    companion object {
        init {
            System.loadLibrary("sowz_aep")
        }
    }
}
