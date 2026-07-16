package com.whispertype.keyboard.voice

data class AsrModel(
    val id: String,
    val title: String,
    val description: String,
    val downloadUrl: String,
    val archiveName: String,
    val folderName: String,
    val approxSizeMb: Int,
    val kind: Kind,
    val whisperPrefix: String = "",
    val recommended: Boolean = false
) {
    enum class Kind { WHISPER, PARAKEET }
}

object ModelCatalog {
    val models: List<AsrModel> = listOf(
        AsrModel(
            id = "parakeet-110m-en",
            title = "Parakeet TDT-CTC 110M EN",
            description = "Recommended — NVIDIA Parakeet, good English quality.",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
            archiveName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
            folderName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
            approxSizeMb = 100,
            kind = AsrModel.Kind.PARAKEET,
            recommended = true
        ),
        AsrModel(
            id = "whisper-tiny-en",
            title = "Whisper Tiny.en",
            description = "Fast English Whisper (int8).",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
            archiveName = "sherpa-onnx-whisper-tiny.en.tar.bz2",
            folderName = "sherpa-onnx-whisper-tiny.en",
            approxSizeMb = 113,
            kind = AsrModel.Kind.WHISPER,
            whisperPrefix = "tiny.en"
        )
    )

    fun byId(id: String): AsrModel =
        models.firstOrNull { it.id == id } ?: models.first()
}
