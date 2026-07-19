package me.trion.whispertype.voice

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer

class WhisperEngine(
    initializerFile: File,
    encoderFile: File,
    decoderFile: File,
    cacheInitFile: File,
    detokenizerFile: File,
) {
    private val env = OnnxRuntime.env
    private val initSession: OrtSession
    private val encoderSession: OrtSession
    private val decoderSession: OrtSession
    private val cacheInitSession: OrtSession
    private val detokenizerSession: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }
        val encOpts = OrtSession.SessionOptions().apply {
            registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            setSymbolicDimensionValue("batch_size", 1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }
        initSession = OnnxRuntime.loadSession(initializerFile, opts)
        encoderSession = OnnxRuntime.loadSession(encoderFile, encOpts)
        decoderSession = OnnxRuntime.loadSession(decoderFile, opts)
        cacheInitSession = OnnxRuntime.loadSession(cacheInitFile, opts)
        detokenizerSession = OnnxRuntime.loadSession(detokenizerFile, opts)
    }

    fun transcribe(pcmFloats: FloatArray): String {
        val initResult = floatTensor(
            pcmFloats,
            longArrayOf(1, pcmFloats.size.toLong())
        ).use { audioTensor ->
            initSession.run(mapOf("audio_pcm" to audioTensor))
        }
        val encResult = initResult.use {
            val melTensor = it.get(0) as OnnxTensor
            encoderSession.run(mapOf("input_features" to melTensor))
        }
        val cacheResult = encResult.use {
            val encoderHidden = it.get(0) as OnnxTensor
            cacheInitSession.run(mapOf("encoder_hidden_states" to encoderHidden))
        }

        val initialTokens = intArrayOf(
            ModelConfig.START_TOKEN,
            ModelConfig.EN_TOKEN,
            ModelConfig.TRANSCRIBE_TOKEN,
            ModelConfig.NOTIMESTAMPS_TOKEN
        )
        val maxTokens = ((pcmFloats.size / 16000) * 30).coerceIn(4, 448)
        val completeOutput = mutableListOf<Int>()
        var prevResult: OrtSession.Result? = null
        var previousToken = ModelConfig.NOTIMESTAMPS_TOKEN
        var step = 0
        var eosReached = false

        try {
            while (!eosReached && step < maxTokens) {
                val inputId = if (step < initialTokens.size) initialTokens[step] else previousToken

                val inputIdsTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(inputId.toLong())), longArrayOf(1, 1)
                )

                val decoderInput = mutableMapOf<String, OnnxTensor>()
                decoderInput["input_ids"] = inputIdsTensor

                var zeroPast: OnnxTensor? = null
                if (step == 0) {
                    zeroPast = zeroPastTensor()
                    for (i in 0 until ModelConfig.N_DECODER_LAYERS) {
                        decoderInput["past_key_values.$i.decoder.key"] = zeroPast
                        decoderInput["past_key_values.$i.decoder.value"] = zeroPast
                        decoderInput["past_key_values.$i.encoder.key"] =
                            cacheResult.get("present.$i.encoder.key").get() as OnnxTensor
                        decoderInput["past_key_values.$i.encoder.value"] =
                            cacheResult.get("present.$i.encoder.value").get() as OnnxTensor
                    }
                } else {
                    for (i in 0 until ModelConfig.N_DECODER_LAYERS) {
                        decoderInput["past_key_values.$i.decoder.key"] =
                            prevResult!!.get("present.$i.decoder.key").get() as OnnxTensor
                        decoderInput["past_key_values.$i.decoder.value"] =
                            prevResult!!.get("present.$i.decoder.value").get() as OnnxTensor
                        decoderInput["past_key_values.$i.encoder.key"] =
                            cacheResult.get("present.$i.encoder.key").get() as OnnxTensor
                        decoderInput["past_key_values.$i.encoder.value"] =
                            cacheResult.get("present.$i.encoder.value").get() as OnnxTensor
                    }
                }

                val result = try {
                    decoderSession.run(decoderInput)
                } finally {
                    inputIdsTensor.close()
                    zeroPast?.close()
                }
                val logitsTensor = result.get("logits").get() as OnnxTensor
                val logitsValue = logitsTensor.value as Array<Array<FloatArray>>
                val logitsRow = logitsValue[0][0]

                var maxIdx = 0
                for (i in 1 until logitsRow.size) {
                    if (logitsRow[i] > logitsRow[maxIdx]) maxIdx = i
                }

                if (maxIdx == ModelConfig.EOS_TOKEN) {
                    eosReached = true
                } else {
                    // Keep the prompt-step predictions in the sequence. The
                    // model's detokenizer expects the complete decoded sequence.
                    completeOutput.add(maxIdx)
                }
                previousToken = maxIdx

                step++
                prevResult?.close()
                prevResult = result
            }

            if (completeOutput.isEmpty()) {
                return ""
            }

            val seqArray = completeOutput.toIntArray()
            val seqTensor = OnnxTensor.createTensor(
                env, IntBuffer.wrap(seqArray), longArrayOf(1, 1, seqArray.size.toLong())
            )
            val text = try {
                detokenizerSession.run(mapOf("sequences" to seqTensor)).use { result ->
                    val textResult = result.get(0).value as Array<Array<String>>
                    textResult[0][0]
                }
            } finally {
                seqTensor.close()
            }

            return correctText(text.trim())
        } finally {
            prevResult?.close()
            cacheResult.close()
        }
    }

    private fun correctText(text: String): String {
        var t = text
        t = t.replace(Regex("<\\|[^>]*\\|> "), "")
        t = t.trim()
        if (t.length >= 2 && t[0].isLowerCase()) {
            t = t.replaceFirstChar { it.uppercaseChar() }
        }
        t = t.replace("...", "")
        return t
    }

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data)
        bb.rewind()
        return OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.FLOAT)
    }

    private fun zeroPastTensor(): OnnxTensor {
        val shape = longArrayOf(1, ModelConfig.N_HEADS.toLong(), 0, ModelConfig.HEAD_DIM.toLong())
        val bb = ByteBuffer.allocateDirect(0)
        return OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.FLOAT)
    }

    fun close() {
        listOf(initSession, encoderSession, decoderSession, cacheInitSession, detokenizerSession)
            .forEach { session -> runCatching { session.close() } }
    }
}
