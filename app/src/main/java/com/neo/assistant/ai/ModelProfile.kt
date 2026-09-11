package com.neo.assistant.ai

enum class ModelProfile(
    val id: String,
    val label: String,
    val modelName: String,
    val description: String,
    val approxSizeGb: Double,
    val fileName: String,
    val downloadUrl: String,
    val minimumBytes: Long,
    val contextSize: Int,
    val maxTokens: Int
) {
    FAST(
        id = "fast",
        label = "FAST",
        modelName = "Qwen3 1.7B",
        description = "เร็วมาก / ใช้แบตน้อย / เหมาะกับแชตทั่วไป เปิดแอป และตั้งปลุก",
        approxSizeGb = 1.3,
        fileName = "Qwen3-1.7B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true",
        minimumBytes = 1_000_000_000L,
        contextSize = 4096,
        maxTokens = 192
    ),
    BALANCED(
        id = "balanced",
        label = "BALANCED",
        modelName = "Qwen3 4B",
        description = "เร็ว + ฉลาด / เหมาะกับการใช้งานประจำ / ค่าเริ่มต้น",
        approxSizeGb = 2.5,
        fileName = "Qwen3-4B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true",
        minimumBytes = 2_000_000_000L,
        contextSize = 4096,
        maxTokens = 256
    ),
    SMART(
        id = "smart",
        label = "SMART",
        modelName = "Qwen3 8B",
        description = "คิดเก่งกว่า / เหมาะกับเขียนโปรแกรม วิเคราะห์ และวางแผน",
        approxSizeGb = 5.0,
        fileName = "Qwen3-8B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf?download=true",
        minimumBytes = 4_000_000_000L,
        contextSize = 4096,
        maxTokens = 320
    ),
    ULTRA(
        id = "ultra",
        label = "ULTRA",
        modelName = "Qwen3 14B",
        description = "ฉลาดที่สุดในชุด / หนักกว่า / เหมาะกับงานยากและตอนเสียบชาร์จ",
        approxSizeGb = 9.0,
        fileName = "Qwen3-14B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen3-14B-GGUF/resolve/main/Qwen3-14B-Q4_K_M.gguf?download=true",
        minimumBytes = 7_500_000_000L,
        contextSize = 4096,
        maxTokens = 384
    );

    companion object {
        fun fromId(id: String?): ModelProfile = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}
