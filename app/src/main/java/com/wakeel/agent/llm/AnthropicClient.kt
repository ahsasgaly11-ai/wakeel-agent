package com.wakeel.agent.llm

import com.wakeel.agent.core.Shot
import com.wakeel.agent.core.Usage
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Claude Messages API with a forced tool call and prompt caching on the static prefix (tools + system). */
class AnthropicClient(private val key: String, private val base: String) : LlmClient {

    override suspend fun callTool(model: String, system: String, user: String, shot: Shot?, tool: ToolSpec, maxTokens: Int): ToolResult {
        val content = JSONArray()
        if (shot != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", shot.base64),
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", user))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("tools", JSONArray().put(JSONObject().put("name", tool.name).put("description", tool.description).put("input_schema", tool.schema)))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", tool.name))
            .put(
                "system", JSONArray().put(
                    JSONObject().put("type", "text").put("text", system).put("cache_control", JSONObject().put("type", "ephemeral"))
                )
            )
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val req = Request.Builder()
            .url(base.trimEnd('/') + "/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val o = JSONObject(Http.post(req))
        val blocks = o.optJSONArray("content") ?: JSONArray()
        var input: JSONObject? = null
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "tool_use") {
                input = b.optJSONObject("input"); break
            }
        }
        val u = o.optJSONObject("usage")
        val usage = Usage(
            u?.optInt("input_tokens") ?: 0,
            u?.optInt("output_tokens") ?: 0,
            u?.optInt("cache_read_input_tokens") ?: 0,
            u?.optInt("cache_creation_input_tokens") ?: 0,
        )
        return ToolResult(input, usage)
    }
}
