package me.trion.whispertype.voice

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

object OnnxRuntime {
    val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    fun loadSession(modelFile: File, options: OrtSession.SessionOptions? = null): OrtSession {
        val opts = options ?: OrtSession.SessionOptions()
        return env.createSession(modelFile.absolutePath, opts)
    }
}
