package me.trion.whispertype.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64

class CtcEngine(
    private val modelSession: OrtSession,
    private val initializerSession: OrtSession,
    private val tokensFile: File,
) {
    private val env = OnnxRuntime.getEnv()
    private val tokenTable: List<String> = run {
        tokensFile.readLines().map { line ->
            line.substringBeforeLast(' ')
        }
    }

    fun transcribe(pcmFloats: FloatArray): String {
        val pcmTensor = OnnxTensor.createTensor(
            env, longArrayOf(pcmFloats.size.toLong()), FloatBuffer.wrap(pcmFloats))
        val initResult = initializerSession.run(
            mapOf(WhisperModelConfig.INITIALIZER_INPUT to pcmTensor))
        val melTensor = initResult.get(0) as OnnxTensor
        val melShape = melTensor.info.shape
        val numFrames = melShape[2].toInt()
        val melBuf = FloatArray(80 * numFrames)
        melTensor.floatBuffer.get(melBuf)
        initResult.close()

        val transposed = FloatArray(numFrames * 80)
        for (t in 0 until numFrames) {
            for (f in 0 until 80) {
                transposed[t * 80 + f] = melBuf[f * numFrames + t]
            }
        }

        val inputTensor = OnnxTensor.createTensor(
            env, longArrayOf(1, numFrames.toLong(), 80L), FloatBuffer.wrap(transposed))
        val lengthTensor = OnnxTensor.createTensor(
            env, longArrayOf(1), LongBuffer.wrap(longArrayOf(numFrames.toLong())))
        val result = modelSession.run(mapOf(
            CtcModelConfig.INPUT to inputTensor,
            CtcModelConfig.INPUT_LENGTH to lengthTensor,
        ))
        val logProbs = result.get(0) as OnnxTensor
        val logitsShape = logProbs.info.shape
        val tDim = logitsShape[1].toInt()
        val vocabSize = logitsShape[2].toInt()
        val buf = FloatArray(tDim * vocabSize)
        logProbs.floatBuffer.get(buf)
        result.close()

        val tokenIds = ctcGreedyDecode(buf, tDim, vocabSize)
        return decodeTokens(tokenIds)
    }

    private fun ctcGreedyDecode(logProbs: FloatArray, tDim: Int, vocabSize: Int): List<Int> {
        val result = mutableListOf<Int>()
        var prevId = -1
        for (t in 0 until tDim) {
            val offset = t * vocabSize
            var maxIdx = 0
            var maxVal = logProbs[offset]
            for (i in 1 until vocabSize) {
                if (logProbs[offset + i] > maxVal) {
                    maxVal = logProbs[offset + i]
                    maxIdx = i
                }
            }
            if (maxIdx != 0 && maxIdx != prevId) {
                result.add(maxIdx)
            }
            prevId = maxIdx
        }
        return result
    }

    private fun decodeTokens(tokenIds: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (id in tokenIds) {
            if (id < 0 || id >= tokenTable.size) continue
            val b64 = tokenTable[id]
            try {
                val decoded = Base64.getDecoder().decode(b64)
                bytes.write(decoded)
            } catch (_: Exception) { }
        }
        return bytes.toString(Charsets.UTF_8).trim()
    }
}
