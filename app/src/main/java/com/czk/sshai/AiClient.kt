package com.czk.sshai

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
 * 任何 OpenAI 兼容的 /chat/completions 接口都能用（DeepSeek / 通义 / Kimi / Ollama / one-api …）。
 */
class AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun chat(cfg: SshConfig, system: String, turns: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            if (cfg.aiKey.isBlank()) throw IllegalStateException("没填 AI API Key")

            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            turns.forEach { (role, content) ->
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            val body = JSONObject()
                .put("model", cfg.aiModel.ifBlank { "deepseek-chat" })
                .put("messages", messages)
                .put("temperature", 0.2)
                .put("stream", false)

            val req = Request.Builder()
                .url(endpoint(cfg.aiBaseUrl))
                .addHeader("Authorization", "Bearer ${cfg.aiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("AI 接口 ${resp.code}：${text.take(240)}")
                }
                JSONObject(text)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("返回格式不认识：${text.take(240)}")
            }
        }

    private fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    companion object {
        private val FENCE = Regex("```(?:bash|sh|shell|zsh)?\\s*\\n([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

        /** 从模型回复里抽出可执行命令（代码块内的非注释行）。 */
        fun extractCommands(reply: String): List<String> {
            val out = mutableListOf<String>()
            FENCE.findAll(reply).forEach { m ->
                m.groupValues[1].lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { out.add(it) }
            }
            return out.distinct()
        }
    }
}
