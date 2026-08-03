package com.scanqa.app.ai

import com.scanqa.app.AiProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls any OpenAI-compatible /chat/completions endpoint
 * (DeepSeek, OpenAI, 通义, Moonshot, etc.).
 */
object AiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * @param profile 使用哪一个已配置的 AI 模型（在设置里可以配置多个，使用时切换）
     * @param question OCR 识别出的文字（可能为空，比如纯图形题）
     * @param imageBase64 拍到的题目图片（JPEG，Base64 编码，不含 data:xxx 前缀），传 null 表示不带图。
     *   注意：只有支持视觉输入（vision）的模型才能"看到"这张图，比如 gpt-4o、gpt-4o-mini、
     *   glm-4v-flash、qwen-vl-plus、moonshot-v1-vision 等。纯文本模型（如默认的 deepseek-chat）
     *   会直接忽略图片，只根据文字回答。
     */
    suspend fun answer(
        profile: AiProfile,
        question: String,
        imageBase64: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = profile.apiKey
        if (apiKey.isBlank()) {
            return@withContext "⚠️ 「${profile.name}」还没有配置 API Key。请回到主页 → 设置，填入 API Key。"
        }
        val base = profile.baseUrl.trimEnd('/')
        val url = "$base/chat/completions"
        val model = profile.model

        val userContent: Any = if (imageBase64 != null) {
            // 多模态格式（OpenAI vision 兼容）：文字 + 图片一起发给模型
            JSONArray().apply {
                val textPart = if (question.isBlank()) {
                    "这是一道题目的照片，图中可能包含文字、图形、表格或函数图像。请结合图片内容直接解答。"
                } else {
                    "这是题目照片，OCR 识别出的文字如下（可能有误或不完整，请以图片实际内容为准）：\n$question"
                }
                put(JSONObject().put("type", "text").put("text", textPart))
                put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                    )
                )
            }
        } else {
            question
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put(
                "content",
                "你是一个解题助手，擅长解答包含图形、图表、函数图像等的题目。请直接给出题目的答案，" +
                    "如果是选择题先给出正确选项，再用一两句话简要说明理由；如果题目涉及图片中的图形" +
                    "（如几何图、坐标系、表格），请结合图片内容作答。回答使用中文。"
            ))
            put(JSONObject().put("role", "user").put("content", userContent))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.2)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val niceMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    } catch (e: Exception) {
                        null
                    }
                    return@withContext buildString {
                        append("❌ 「${profile.name}」请求失败 (HTTP ${resp.code})")
                        if (!niceMsg.isNullOrBlank()) {
                            append("\n原因：$niceMsg")
                        }
                        append("\n\n完整返回：\n$body")
                    }
                }
                val json = JSONObject(body)
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                if (content.isBlank()) "（模型没有返回内容）\n$body" else content.trim()
            }
        } catch (e: Exception) {
            "❌ 「${profile.name}」网络错误：${e.message}"
        }
    }
}
