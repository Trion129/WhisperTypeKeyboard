package me.trion.whispertype.voice

import java.io.File

sealed interface ModelKind {
    data object WHISPER : ModelKind
    data object PARAKEET_CTC : ModelKind
}

data class ModelConfig(
    val kind: ModelKind,
    val modelDir: File,
    val tokensFile: File? = null,
)

object WhisperModelConfig {
    const val ENCODER_INPUT = "mel"
    const val ENCODER_OUTPUT_K = "n_layer_cross_k"
    const val ENCODER_OUTPUT_V = "n_layer_cross_v"

    const val DECODER_INPUT_IDS = "tokens"
    const val DECODER_INPUT_SELF_K = "in_n_layer_self_k_cache"
    const val DECODER_INPUT_SELF_V = "in_n_layer_self_v_cache"
    const val DECODER_INPUT_CROSS_K = "n_layer_cross_k"
    const val DECODER_INPUT_CROSS_V = "n_layer_cross_v"
    const val DECODER_INPUT_OFFSET = "offset"
    const val DECODER_OUTPUT_LOGITS = "logits"
    const val DECODER_OUTPUT_SELF_K = "out_n_layer_self_k_cache"
    const val DECODER_OUTPUT_SELF_V = "out_n_layer_self_v_cache"

    const val INITIALIZER_INPUT = "audio_pcm"
    const val INITIALIZER_OUTPUT = "mel"
    const val DETOKENIZER_INPUT = "sequences"
    const val DETOKENIZER_OUTPUT = "token_ids"
}

object CtcModelConfig {
    const val INPUT = "audio_signal"
    const val INPUT_LENGTH = "length"
    const val OUTPUT = "logprobs"
}
