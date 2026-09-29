package com.example.aikeyboardoverlay.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LlamaEngine {

    private var modelHandle: Long = 0L
    private var isLoaded = false

    companion object {
        private const val TAG = "LlamaEngine"
        init {
            System.loadLibrary("native-lib")
        }
    }

    private external fun nativeLoadModel(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFreeModel(handle: Long)

    suspend fun loadModel(modelFile: File, nThreads: Int = 4, nCtx: Int = 2048): Boolean {
        return withContext(Dispatchers.IO) {
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found at ${modelFile.absolutePath}")
                return@withContext false
            }
            modelHandle = nativeLoadModel(modelFile.absolutePath, nThreads, nCtx)
            isLoaded = modelHandle != 0L
            if (!isLoaded) Log.e(TAG, "Native model load failed")
            isLoaded
        }
    }

    suspend fun generateResponse(prompt: String, maxTokens: Int = 128): String {
        if (!isLoaded) {
            Log.e(TAG, "generateResponse called before loadModel() succeeded")
            return ""
        }
        return withContext(Dispatchers.Default) {
            nativeGenerate(modelHandle, prompt, maxTokens)
        }
    }

    fun release() {
        if (isLoaded) {
            nativeFreeModel(modelHandle)
            modelHandle = 0L
            isLoaded = false
        }
    }
}
