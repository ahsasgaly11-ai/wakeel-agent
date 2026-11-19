package com.wakeel.agent.llm

import com.wakeel.agent.core.Shot
import com.wakeel.agent.core.Usage
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Claude Messages API, prompt caching on the static prefix (tools + system).
 *
 * Newer models (Sonnet 5.5, Opus 5.5, Fable/Mythos 5.x) reject a forced tool_choice and think by default.
 * For them we send tool_choice=auto + an explicit instruction, turn up-front thinking off where supported,
 * and leave room in max_tokens. Unknown models that reject forced tools are detected once and remembered.
 */
class AnthropicClient(private val key: String, private val base: String) : LlmClient {

    companion object {
        private val noForcedTools: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private val noBetweenTools: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        private fun knownNoForced(model: String): Boolean {
            val m = model.lowercase()
            return listOf("sonnet-5-5", "opus-5-5", "fable-5", "mythos").any { m.contains(it) }
        }
    }

    override suspend fun callTool(
        model: String, system: String, user: String, shot: Shot?, tool: ToolSpec, maxTokens: Int, extraSystem: String?,
    ): ToolResult {
        val auto = knownNoForced(model) || model in noForcedTools
        return try {
            send(model, system, user, shot, tool, maxTokens, auto, extraSystem)
        } catch (e: LlmException) {
            val msg = e.message.orEmpty()
            when {
                !auto && msg.contains("tool_choice") -> {
                    noForcedTools += model
                    send(model, system, user, shot, tool, maxTokens, true, extraSystem)
                }
                auto && msg.contains("thinking") && model !in noBetweenTools -> {
                    noBetweenTools += model
                    send(model, system, user, shot, tool, maxTokens, true, extraSystem)
                }
                else -> throw e
            }
        }
    }

    private suspend fun send(
        model: String, system: String, user: String, shot: Shot?, tool: ToolSpec, maxTokens: Int, auto: Boolean, extra: String?,
    ): ToolResult {
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

        val sys = if (auto) "$system\n\nIMPORTANT: Always respond by calling the `${tool.name}` tool exactly once. Never answer with plain text." else system

        val ephemeral = JSONObject().put("type", "ephemeral")
        val sysBlocks = JSONArray().put(JSONObject().put("type", "text").put("text", sys).put("cache_control", ephemeral))
        if (!extra.isNullOrBlank()) sysBlocks.put(JSONObject().put("type", "text").put("text", extra).put("cache_control", ephemeral))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", if (auto) maxOf(maxTokens, 6000) else maxTokens)
            .put("tools", JSONArray().put(JSONObject().put("name", tool.name).put("description", tool.description).put("input_schema", tool.schema)))
            .put("system", sysBlocks)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        if (auto) {
            body.put("tool_choice", JSONObject().put("type", "auto"))
            body.put("output_config", JSONObject().put("effort", "medium"))
            if (model.lowercase().contains("sonnet-5-5") && model !in noBetweenTools) {
                body.put("thinking", JSONObject().put("type", "between_tools"))   // no up-front thinking → cheaper, faster
            }
        } else {
            body.put("tool_choice", JSONObject().put("type", "tool").put("name", tool.name))
        }

        val req = Request.Builder()
            .url(base.trimEnd('/') + "/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val o = JSONObject(Http.post(req))
        val blocks = o.optJSONArray("content") ?: JSONArray()
        var input: JSONObject? = null
        var text = ""
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "tool_use" -> if (input == null && b.optString("name") == tool.name) input = b.optJSONObject("input")
                "text" -> text += b.optString("text")
            }
        }
        // Fallback: a model that answered in text with JSON instead of calling the tool
        if (input == null && text.contains('{')) {
            val a = text.indexOf('{')
            val z = text.lastIndexOf('}')
            if (z > a) input = runCatching { JSONObject(text.substring(a, z + 1)) }.getOrNull()
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
