import Foundation

struct ToolSpec {
    let name: String
    let description: String
    let schema: JSON
}

struct ToolResult {
    let input: JSON?
    let usage: Usage
}

/// A JPEG picture sent to the model (a page screenshot, or a photo the owner attached).
struct Shot {
    let base64: String
}

struct LlmError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

protocol LlmClient {
    /// One forced tool call. Every request the app makes goes through here.
    func callTool(model: String, system: String, user: String, shots: [Shot], tool: ToolSpec, maxTokens: Int, extraSystem: String?) async throws -> ToolResult
}

enum LLM {
    /// Test hook: the self-test replaces the network with a scripted client. Never set in normal use.
    static var override: LlmClient?

    static func create(_ s: SettingsStore) -> LlmClient {
        if let o = override { return o }
        return s.provider == "anthropic" ? AnthropicClient(key: s.apiKey, base: s.baseUrl) : OpenAiClient(key: s.apiKey, base: s.baseUrl)
    }
}

enum Http {
    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 90
        c.timeoutIntervalForResource = 180
        return URLSession(configuration: c)
    }()

    /// POST with retries on rate limits, overload and passing network errors. Returns the decoded body.
    static func post(_ url: String, headers: [String: String], body: JSON) async throws -> JSON {
        guard let u = URL(string: url) else { throw LlmError(message: "عنوان الخادم غير صحيح: \(url)") }
        var req = URLRequest(url: u)
        req.httpMethod = "POST"
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        req.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])

        var last = LlmError(message: "فشل الطلب")
        for attempt in 0..<3 {
            try Task.checkCancellation()
            do {
                let (data, response) = try await session.data(for: req)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                let text = String(data: data, encoding: .utf8) ?? ""
                if (200..<300).contains(code) {
                    guard let o = J.parse(text) as? JSON else { throw LlmError(message: "ردّ غير مفهوم من الخادم.") }
                    return o
                }
                last = LlmError(message: friendly(code, text))
                if !(code == 429 || code == 529 || code >= 500) { throw last }
            } catch let e as LlmError {
                throw e
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if (error as? URLError)?.code == .cancelled { throw CancellationError() }
                last = LlmError(message: "تعذّر الاتصال بالخادم. تحقّق من الإنترنت.")
            }
            try await Task.sleep(nanoseconds: UInt64(1_500_000_000) * UInt64(attempt + 1))
        }
        throw last
    }

    private static func friendly(_ code: Int, _ body: String) -> String {
        var msg = ""
        if let o = J.parse(body) as? JSON {
            msg = J.str((o["error"] as? JSON)?["message"]) ?? J.str(o["message"]) ?? J.str(o["error"]) ?? ""
        }
        msg = msg.take(180)
        let low = msg.lowercased()
        if code == 401 || code == 403 { return "مفتاح API غير صالح أو لا يملك صلاحية." }
        if low.contains("tool_choice") || low.contains("thinking") { return "إعداد غير مدعوم لهذا النموذج: \(msg)" }
        if low.contains("credit") || low.contains("balance") || low.contains("billing") || low.contains("quota") {
            return "الرصيد غير كافٍ في حساب الـ API. اشحن الرصيد ثم أعد المحاولة."
        }
        if code == 404 || (low.contains("model") && code == 400) { return "اسم النموذج أو عنوان الخادم غير صحيح: \(msg)" }
        if code == 429 { return "تجاوزت حد الطلبات، حاول بعد قليل." }
        if code == 529 { return "الخادم مشغول حالياً، حاول بعد قليل." }
        return "خطأ من الخادم (\(code)): \(msg)"
    }
}

/// Claude Messages API, with prompt caching on the static prefix (tools + system).
///
/// Newer models reject a forced tool_choice and think by default. For them the request uses tool_choice=auto
/// with an explicit instruction and leaves room in max_tokens. Unknown models that reject forced tools are
/// detected once and remembered.
final class AnthropicClient: LlmClient {
    private let key: String
    private let base: String
    private static var noForcedTools = Set<String>()
    private static var noBetweenTools = Set<String>()

    init(key: String, base: String) {
        self.key = key
        self.base = base
    }

    private static func knownNoForced(_ model: String) -> Bool {
        let m = model.lowercased()
        return ["sonnet-5-5", "opus-5-5", "fable-5", "mythos"].contains { m.contains($0) }
    }

    func callTool(model: String, system: String, user: String, shots: [Shot], tool: ToolSpec, maxTokens: Int, extraSystem: String?) async throws -> ToolResult {
        let auto = AnthropicClient.knownNoForced(model) || AnthropicClient.noForcedTools.contains(model)
        do {
            return try await send(model, system, user, shots, tool, maxTokens, auto, extraSystem)
        } catch let e as LlmError {
            if !auto && e.message.contains("tool_choice") {
                AnthropicClient.noForcedTools.insert(model)
                return try await send(model, system, user, shots, tool, maxTokens, true, extraSystem)
            }
            if auto && e.message.contains("thinking") && !AnthropicClient.noBetweenTools.contains(model) {
                AnthropicClient.noBetweenTools.insert(model)
                return try await send(model, system, user, shots, tool, maxTokens, true, extraSystem)
            }
            throw e
        }
    }

    private func send(_ model: String, _ system: String, _ user: String, _ shots: [Shot], _ tool: ToolSpec, _ maxTokens: Int, _ auto: Bool, _ extra: String?) async throws -> ToolResult {
        var content: [JSON] = shots.map { shot -> JSON in
            let source: JSON = ["type": "base64", "media_type": "image/jpeg", "data": shot.base64]
            return ["type": "image", "source": source]
        }
        content.append(["type": "text", "text": user])
        let sys = auto ? system + "\n\nIMPORTANT: Always respond by calling the `\(tool.name)` tool exactly once. Never answer with plain text." : system
        let ephemeral: JSON = ["type": "ephemeral"]
        var sysBlocks: [JSON] = [["type": "text", "text": sys, "cache_control": ephemeral]]
        if let e = extra, !e.trimmed.isEmpty { sysBlocks.append(["type": "text", "text": e, "cache_control": ephemeral]) }
        let toolDef: JSON = ["name": tool.name, "description": tool.description, "input_schema": tool.schema]
        let message: JSON = ["role": "user", "content": content]
        var body: JSON = [
            "model": model,
            "max_tokens": auto ? max(maxTokens, 6000) : maxTokens,
            "tools": [toolDef],
            "system": sysBlocks,
            "messages": [message],
        ]
        if auto {
            body["tool_choice"] = ["type": "auto"]
            body["output_config"] = ["effort": "medium"]
            if model.lowercased().contains("sonnet-5-5") && !AnthropicClient.noBetweenTools.contains(model) {
                body["thinking"] = ["type": "between_tools"]      // no up-front thinking: cheaper and faster
            }
        } else {
            body["tool_choice"] = ["type": "tool", "name": tool.name]
        }
        var root = base
        while root.hasSuffix("/") { root.removeLast() }
        let o = try await Http.post(root + "/v1/messages", headers: ["x-api-key": key, "anthropic-version": "2023-06-01"], body: body)

        var input: JSON?
        var text = ""
        for b in (o["content"] as? [Any])?.compactMap({ $0 as? JSON }) ?? [] {
            switch J.str(b["type"]) ?? "" {
            case "tool_use":
                if input == nil && J.str(b["name"]) == tool.name { input = b["input"] as? JSON }
            case "text":
                text += J.str(b["text"]) ?? ""
            default:
                break
            }
        }
        // A model that answered in text with JSON instead of calling the tool
        if input == nil { input = J.object(in: text) }
        let u = o["usage"] as? JSON
        let usage = Usage(input: J.int(u?["input_tokens"]) ?? 0, output: J.int(u?["output_tokens"]) ?? 0,
                          cacheRead: J.int(u?["cache_read_input_tokens"]) ?? 0, cacheWrite: J.int(u?["cache_creation_input_tokens"]) ?? 0)
        return ToolResult(input: input, usage: usage)
    }
}

/// Any OpenAI-compatible Chat Completions endpoint (OpenAI, Gemini, OpenRouter, local servers).
final class OpenAiClient: LlmClient {
    private let key: String
    private let base: String

    init(key: String, base: String) {
        self.key = key
        self.base = base
    }

    func callTool(model: String, system: String, user: String, shots: [Shot], tool: ToolSpec, maxTokens: Int, extraSystem: String?) async throws -> ToolResult {
        var sys = system
        if let e = extraSystem, !e.trimmed.isEmpty { sys += "\n\n" + e }
        var userContent: [JSON] = [["type": "text", "text": user]]
        for shot in shots {
            let image: JSON = ["url": "data:image/jpeg;base64," + shot.base64]
            userContent.append(["type": "image_url", "image_url": image])
        }
        let function: JSON = ["name": tool.name, "description": tool.description, "parameters": tool.schema]
        let toolDef: JSON = ["type": "function", "function": function]
        let choiceFn: JSON = ["name": tool.name]
        let systemMsg: JSON = ["role": "system", "content": sys]
        let userMsg: JSON = ["role": "user", "content": userContent]
        var body: JSON = [
            "model": model,
            "messages": [systemMsg, userMsg],
            "tools": [toolDef],
            "tool_choice": ["type": "function", "function": choiceFn] as JSON,
        ]
        if base.contains("api.openai.com") { body["max_completion_tokens"] = maxTokens } else { body["max_tokens"] = maxTokens }
        var root = base
        while root.hasSuffix("/") { root.removeLast() }
        let o = try await Http.post(root + "/chat/completions", headers: ["Authorization": "Bearer \(key)", "X-Title": "Wakeel"], body: body)

        let msg = ((o["choices"] as? [Any])?.first as? JSON)?["message"] as? JSON
        let call = ((msg?["tool_calls"] as? [Any])?.first as? JSON)?["function"] as? JSON
        var input: JSON?
        if let args = call?["arguments"] {
            input = (args as? JSON) ?? (args as? String).flatMap { J.object(in: $0) }
        }
        if input == nil, let content = J.str(msg?["content"]) { input = J.object(in: content) }
        let u = o["usage"] as? JSON
        let prompt = J.int(u?["prompt_tokens"]) ?? 0
        let cached = J.int((u?["prompt_tokens_details"] as? JSON)?["cached_tokens"]) ?? 0
        return ToolResult(input: input, usage: Usage(input: prompt - cached, output: J.int(u?["completion_tokens"]) ?? 0, cacheRead: cached, cacheWrite: 0))
    }
}
