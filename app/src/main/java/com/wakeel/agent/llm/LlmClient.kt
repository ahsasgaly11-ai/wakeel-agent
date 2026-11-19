package com.wakeel.agent.llm

import com.wakeel.agent.core.AgentDecision
import com.wakeel.agent.core.Settings
import com.wakeel.agent.core.Shot
import com.wakeel.agent.core.Usage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LlmResult(val decision: AgentDecision, val usage: Usage)

data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

data class ToolResult(val input: JSONObject?, val usage: Usage)

class LlmException(message: String) : Exception(message)

interface LlmClient {
    /** One forced tool call. Every request the app makes goes through here. */
    /** [extraSystem] is a second cached system block (app knowledge) that stays identical across a task's calls. */
    suspend fun callTool(
        model: String, system: String, user: String, shot: Shot?, tool: ToolSpec,
        maxTokens: Int = 1800, extraSystem: String? = null,
    ): ToolResult

    suspend fun decide(model: String, user: String, shot: Shot?, knowledge: String? = null): LlmResult {
        val r = callTool(model, Prompt.SYSTEM, user, shot, Prompt.actTool(), extraSystem = knowledge)
        return LlmResult(r.input?.let { AgentDecision.parse(it) } ?: AgentDecision("", emptyList(), false), r.usage)
    }

    companion object {
        /** Test hook: the on-device tests replace the network with a scripted client. Never set in the app itself. */
        @Volatile
        var override: ((Settings) -> LlmClient)? = null

        fun create(s: Settings): LlmClient = override?.invoke(s)
            ?: if (s.provider == "anthropic") AnthropicClient(s.apiKey, s.baseUrl) else OpenAiClient(s.apiKey, s.baseUrl)
    }
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val JSON = "application/json; charset=utf-8".toMediaType()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    /** POST with retries on rate limits / overload / transient network errors. Returns the body. */
    suspend fun post(req: Request): String {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                client.newCall(req).await().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (r.isSuccessful) return body
                    val retryable = r.code == 429 || r.code == 529 || r.code >= 500
                    lastError = LlmException(friendly(r.code, body))
                    if (!retryable) throw lastError!!
                }
            } catch (e: IOException) {
                lastError = LlmException("تعذّر الاتصال بالخادم. تحقّق من الإنترنت.")
            }
            delay(1500L * (attempt + 1))
        }
        throw lastError ?: LlmException("فشل الطلب")
    }

    private fun friendly(code: Int, body: String): String {
        val msg = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("message")
        }.getOrNull().orEmpty().take(180)
        return when {
            code == 401 || code == 403 -> "مفتاح API غير صالح أو لا يملك صلاحية."
            msg.contains("tool_choice") || msg.contains("thinking") -> "إعداد غير مدعوم لهذا النموذج: $msg"
            msg.contains("credit", true) || msg.contains("balance", true) || msg.contains("billing", true) ->
                "الرصيد غير كافٍ في حساب الـ API. اشحن الرصيد ثم أعد المحاولة."
            code == 404 || (msg.contains("model", true) && code == 400) -> "اسم النموذج أو عنوان الخادم غير صحيح: $msg"
            code == 429 -> "تجاوزت حد الطلبات، حاول بعد قليل."
            code == 529 -> "الخادم مشغول حالياً، حاول بعد قليل."
            else -> "خطأ من الخادم ($code): $msg"
        }
    }
}
