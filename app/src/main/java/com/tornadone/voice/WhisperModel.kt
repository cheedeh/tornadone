package com.tornadone.voice

enum class WhisperModel(
    val id: String,
    val displayName: String,
    val numLayers: Int,
    val numHeads: Int,
    val dModel: Int,
    val encoderSizeMB: Int,
    val decoderSizeMB: Int,
    val hfRepo: String? = null,
) {
    TINY("tiny", "Tiny", 4, 6, 384, 31, 113),
    TINY_PL("tiny-pl", "Tiny PL", 4, 6, 384, 31, 189),
    BASE("base", "Base", 6, 8, 512, 79, 199),
    SMALL("small", "Small", 12, 12, 768, 337, 587);

    val headDim: Int get() = 64
    val totalSizeMB: Int get() = encoderSizeMB + decoderSizeMB

    private val repo: String get() = hfRepo ?: "onnx-community/whisper-$id"

    val encoderUrl: String
        get() = "https://huggingface.co/$repo/resolve/main/onnx/encoder_model.onnx"

    val decoderUrl: String
        get() = "https://huggingface.co/$repo/resolve/main/onnx/decoder_model_merged.onnx"

    companion object {
        const val ENCODER_FILE = "encoder_model.onnx"
        const val DECODER_FILE = "decoder_model_merged.onnx"

        fun fromId(id: String): WhisperModel? = entries.find { it.id == id }
    }
}
