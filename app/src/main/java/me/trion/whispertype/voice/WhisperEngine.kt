package me.trion.whispertype.voice

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class WhisperEngine(
    private val encoderSession: OrtSession,
    private val decoderSession: OrtSession,
    private val initializerSession: OrtSession,
    private val tokensTxt: File,
) {
    private val env = OnnxRuntime.env
    private val tokenTable: List<String> = run {
        tokensTxt.readLines().map { line ->
            line.substringBeforeLast(' ')
        }
    }

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(data)
        bb.rewind()
        return OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.FLOAT)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocate(data.size * 8).order(ByteOrder.nativeOrder())
        bb.asLongBuffer().put(data)
        bb.rewind()
        return OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.INT64)
    }

    fun transcribe(pcmFloats: FloatArray): String {
        val pcmTensor = floatTensor(pcmFloats, longArrayOf(pcmFloats.size.toLong()))
        val initResult = initializerSession.run(
            mapOf(WhisperModelConfig.INITIALIZER_INPUT to pcmTensor))
        val mel = initResult.get(0) as OnnxTensor
        val melPadded = padMel(mel)
        initResult.close()

        val encResult = encoderSession.run(
            mapOf(WhisperModelConfig.ENCODER_INPUT to melPadded))
        val crossK = encResult.get(WhisperModelConfig.ENCODER_OUTPUT_K) as OnnxTensor
        val crossV = encResult.get(WhisperModelConfig.ENCODER_OUTPUT_V) as OnnxTensor
        melPadded.close()

        val tokenIds = decodeLoop(crossK, crossV)
        encResult.close()

        return decodeTokens(tokenIds)
    }

    private fun decodeLoop(crossK: OnnxTensor, crossV: OnnxTensor): List<Long> {
        val tokenIds = mutableListOf(START_TOKEN, EN_TOKEN, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
        var selfKCache = zeroCache()
        var selfVCache = zeroCache()
        var offset = 0L

        for (step in 0 until MAX_DECODE_LEN) {
            val inputTokens = if (step == 0) tokenIds else listOf(tokenIds.last())
            val idsData = inputTokens.toLongArray()
            val tokensTensor = longTensor(idsData, longArrayOf(1, inputTokens.size.toLong()))
            val offsetTensor = longTensor(longArrayOf(offset), longArrayOf(1))

            val inputs = mapOf(
                WhisperModelConfig.DECODER_INPUT_IDS to tokensTensor,
                WhisperModelConfig.DECODER_INPUT_SELF_K to selfKCache,
                WhisperModelConfig.DECODER_INPUT_SELF_V to selfVCache,
                WhisperModelConfig.DECODER_INPUT_CROSS_K to crossK,
                WhisperModelConfig.DECODER_INPUT_CROSS_V to crossV,
                WhisperModelConfig.DECODER_INPUT_OFFSET to offsetTensor,
            )

            val result = decoderSession.run(inputs)
            val logits = result.get(WhisperModelConfig.DECODER_OUTPUT_LOGITS) as OnnxTensor
            val nextToken = argmaxLast(logits)

            if (step == 0) offset = tokenIds.size.toLong()

            selfKCache = result.get(WhisperModelConfig.DECODER_OUTPUT_SELF_K) as OnnxTensor
            selfVCache = result.get(WhisperModelConfig.DECODER_OUTPUT_SELF_V) as OnnxTensor
            result.close()
            tokensTensor.close()
            offsetTensor.close()

            if (nextToken == EOS_TOKEN || nextToken == NO_SPEECH_TOKEN) break
            tokenIds.add(nextToken)
            offset++
        }

        return tokenIds
    }

    private fun padMel(mel: OnnxTensor): OnnxTensor {
        val shape = mel.info.shape
        val frames = shape[2].toInt()
        val buf = mel.floatBuffer
        val data = FloatArray(N_MEL * frames)
        buf.get(data)

        return if (frames >= MAX_FRAMES) {
            floatTensor(data.copyOf(N_MEL * MAX_FRAMES),
                longArrayOf(1, N_MEL.toLong(), MAX_FRAMES.toLong()))
        } else {
            val padded = FloatArray(N_MEL * MAX_FRAMES)
            System.arraycopy(data, 0, padded, 0, data.size)
            floatTensor(padded, longArrayOf(1, N_MEL.toLong(), MAX_FRAMES.toLong()))
        }
    }

    private fun zeroCache(): OnnxTensor {
        val size = N_DECODER_LAYERS * MAX_DECODE_LEN * D_MODEL
        val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.nativeOrder())
        bb.rewind()
        return OnnxTensor.createTensor(env, bb,
            longArrayOf(N_DECODER_LAYERS.toLong(), 1, MAX_DECODE_LEN.toLong(), D_MODEL.toLong()),
            OnnxJavaType.FLOAT)
    }

    private fun argmaxLast(logits: OnnxTensor): Long {
        val buf = logits.floatBuffer
        val shape = logits.info.shape
        val seqLen = shape[1].toInt()
        val vocabSize = shape[2].toInt()
        val start = (seqLen - 1) * vocabSize
        buf.position(start)
        val row = FloatArray(vocabSize)
        buf.get(row)
        var maxIdx = 0
        for (i in 1 until vocabSize) {
            if (row[i] > row[maxIdx]) maxIdx = i
        }
        return maxIdx.toLong()
    }

    private fun decodeTokens(tokenIds: List<Long>): String {
        val bytes = ByteArrayOutputStream()
        for (id in tokenIds) {
            if (id < 0 || id.toInt() >= tokenTable.size) continue
            val b64 = tokenTable[id.toInt()]
            try {
                val decoded = Base64.getDecoder().decode(b64)
                bytes.write(decoded)
            } catch (_: Exception) { }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val START_TOKEN = 50258L
        const val TRANSCRIBE_TOKEN = 50359L
        const val NOTIMESTAMPS_TOKEN = 50362L
        const val EOS_TOKEN = 50257L
        const val NO_SPEECH_TOKEN = 50363L
        const val EN_TOKEN = 50259L

        const val N_MEL = 80
        const val MAX_FRAMES = 3000
        const val MAX_DECODE_LEN = 448
        const val N_DECODER_LAYERS = 4
        const val D_MODEL = 384
    }
}
