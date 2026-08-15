package com.suzuri.lmdroid.ui.character

/**
 * JNI bindings for the NDK-native MMD renderer (mmd_jni.cpp): PMX/VMD parsing, toon
 * rendering and Bullet physics all run in C++; this object is only the thin bridge.
 * decodeShiftJis is called *from* native code while parsing VMD name fields — VMD is
 * Shift-JIS and Java's charset support stands in for a native transcoding table.
 */
object MmdNative {

    val isAvailable: Boolean

    init {
        isAvailable = runCatching {
            System.loadLibrary("mmd_jni")
        }.isSuccess
    }

    @JvmStatic
    fun decodeShiftJis(bytes: ByteArray): String {
        return String(bytes, charset("Shift_JIS")).trimEnd(' ', '　')
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeLoadModel(handle: Long, pmxPath: String): Boolean
    external fun nativeLoadMotion(handle: Long, vmdPath: String): Boolean
    external fun nativeLastError(handle: Long): String
    external fun nativeTextureCount(handle: Long): Int
    external fun nativeTexturePath(handle: Long, index: Int): String
    external fun nativeInitGl(handle: Long): Boolean
    external fun nativeSetTexture(handle: Long, index: Int, width: Int, height: Int, argbPixels: IntArray)
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeDrawFrame(
        handle: Long,
        dt: Float,
        state: Int,
        mouthOpen: Float,
        lipSync: Boolean,
        zoom: Float,
        panX: Float,
        panY: Float,
    )
}
