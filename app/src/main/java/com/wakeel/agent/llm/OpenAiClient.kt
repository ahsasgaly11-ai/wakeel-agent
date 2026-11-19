package com.wakeel.agent.llm

import com.wakeel.agent.core.AgentDecision
import com.wakeel.agent.core.Shot
import com.wakeel.agent.core.Usage
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Any OpenAI-compatible Chat Completions endpoint (OpenAI, Gemini, OpenRouter, local servers). */
class OpenAiClient(private val key: String, private val base: String) : LlmClient {

    override suspend fun decide(model: String, user: String, shot: Shot?): LlmResult {
        val userContent = JSONArray().put(JSONObject().put("type", "text").put("text", user))
        if (shot != null) {
            userContent.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + shot.base64))
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", Prompt.SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )
            .put(
                "tools", JSONArray().put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", Prompt.TOOL_NAME).put("description", Prompt.TOOL_DESC)
                            .put("parameters", Prompt.schema())
                    )
                )
            )
            .put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", Prompt.TOOL_NAME)))
        if (base.contains("api.openai.com")) body.put("max_completion_tokens", 700) else body.put("max_tokens", 700)

        val req = Request.Builder()
            .url(base.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("X-Title", "Wakeel")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val o = JSONObject(Http.post(req))
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val args = msg?.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")?.optString("arguments")
            ?: msg?.optString("content")
        val parsed = args?.let { extractJson(it) }
        val u = o.optJSONObject("usage")
        val prompt = u?.optInt("prompt_tokens") ?: 0
        val cached = u?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0
        val usage = Usage(prompt - cached, u?.optInt("completion_tokens") ?: 0, cached, 0)
        return LlmResult(parsed?.let { AgentDecision.parse(it) } ?: AgentDecision("", emptyList(), false), usage)
    }

    private fun extractJson(s: String): JSONObject? {
        runCatching { return JSONObject(s) }
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        return if (a >= 0 && b > a) runCatching { JSONObject(s.substring(a, b + 1)) }.getOrNull() else null
    }
}
