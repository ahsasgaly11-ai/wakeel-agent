package com.wakeel.agent.core

import android.content.Context
import java.time.LocalDate

data class Preset(
    val id: String,
    val title: String,
    val base: String,
    val fast: String,
    val strong: String,
    val keyUrl: String,
)

object Presets {
    val all = listOf(
        Preset("anthropic", "Claude", "https://api.anthropic.com", "claude-haiku-5-5", "claude-sonnet-5-5", "https://platform.claude.com/settings/keys"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "", "", "https://platform.openai.com/api-keys"),
        Preset("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "", "", "https://aistudio.google.com/apikey"),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", "", "https://openrouter.ai/keys"),
        Preset("custom", "مخصص", "", "", "", ""),
    )

    fun of(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}

/** What the agent may do inside an app. */
enum class Policy { ALLOWED, READ_ONLY, BLOCKED }

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("wakeel_settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(ctx)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    private fun float(k: String, d: Float) = p.getFloat(k, d)
    private fun putFloat(k: String, v: Float) = p.edit().putFloat(k, v).apply()
    private fun set(k: String): Set<String> = p.getStringSet(k, emptySet())?.toSet() ?: emptySet()
    private fun putSet(k: String, v: Set<String>) = p.edit().putStringSet(k, v).apply()

    // ---- Provider ----
    var provider: String
        get() = str("provider", "anthropic")
        set(v) = putStr("provider", v)

    val preset get() = Presets.of(provider)

    var apiKey: String
        get() = secure.get("key_$provider") ?: ""
        set(v) = secure.put("key_$provider", v.trim())

    var baseUrl: String
        get() = str("base_$provider", preset.base)
        set(v) = putStr("base_$provider", v.trim())

    var fastModel: String
        get() = str("fast_$provider", preset.fast)
        set(v) = putStr("fast_$provider", v.trim())

    var strongModel: String
        get() = str("strong_$provider", preset.strong)
        set(v) = putStr("strong_$provider", v.trim())

    var escalate: Boolean
        get() = bool("escalate", true)
        set(v) = putBool("escalate", v)

    // ---- Budget (USD) ----
    /** Maximum model replies per task. */
    var maxSteps: Int
        get() = p.getInt("max_steps", 25)
        set(v) = p.edit().putInt("max_steps", v.coerceIn(3, 100)).apply()

    var taskBudget: Float
        get() = float("task_budget", 0.05f)
        set(v) = putFloat("task_budget", v)

    var dailyBudget: Float
        get() = float("daily_budget", 1.0f)
        set(v) = putFloat("daily_budget", v)

    // Prices per 1M tokens (defaults: Claude Haiku 5.5 / Sonnet 5.5 list prices)
    var fastIn: Float
        get() = float("p_fast_in", 0.10f)
        set(v) = putFloat("p_fast_in", v)
    var fastOut: Float
        get() = float("p_fast_out", 0.50f)
        set(v) = putFloat("p_fast_out", v)
    var strongIn: Float
        get() = float("p_strong_in", 2.0f)
        set(v) = putFloat("p_strong_in", v)
    var strongOut: Float
        get() = float("p_strong_out", 10.0f)
        set(v) = putFloat("p_strong_out", v)

    // ---- Safety ----
    var confirmSensitive: Boolean
        get() = bool("confirm", true)
        set(v) = putBool("confirm", v)

    var allowScreenshots: Boolean
        get() = bool("screenshots", true)
        set(v) = putBool("screenshots", v)

    /** Package-name keywords the agent must never touch (banks, wallets, password managers). */
    var blockedKeywords: String
        get() = str("blocked_kw2", DEFAULT_BLOCKED)
        set(v) = putStr("blocked_kw2", v)

    /** Package-name keywords the agent may read but never act in (trading apps). */
    var readOnlyKeywords: String
        get() = str("readonly_kw", DEFAULT_READ_ONLY)
        set(v) = putStr("readonly_kw", v)

    var blockedPkgs: Set<String>
        get() = set("blocked_pkgs")
        set(v) = putSet("blocked_pkgs", v)

    var readOnlyPkgs: Set<String>
        get() = set("readonly_pkgs")
        set(v) = putSet("readonly_pkgs", v)

    /** Apps the owner explicitly allowed (overrides keywords; also the whitelist in allowlist mode). */
    var allowedPkgs: Set<String>
        get() = set("allowed_pkgs")
        set(v) = putSet("allowed_pkgs", v)

    var allowlistMode: Boolean
        get() = bool("allowlist_mode", false)
        set(v) = putBool("allowlist_mode", v)

    /** null = automatic (keywords decide). */
    fun explicitPolicy(pkg: String): Policy? = when (pkg) {
        in blockedPkgs -> Policy.BLOCKED
        in readOnlyPkgs -> Policy.READ_ONLY
        in allowedPkgs -> Policy.ALLOWED
        else -> null
    }

    fun setPolicy(pkg: String, policy: Policy?) {
        blockedPkgs = blockedPkgs - pkg
        readOnlyPkgs = readOnlyPkgs - pkg
        allowedPkgs = allowedPkgs - pkg
        when (policy) {
            Policy.BLOCKED -> blockedPkgs = blockedPkgs + pkg
            Policy.READ_ONLY -> readOnlyPkgs = readOnlyPkgs + pkg
            Policy.ALLOWED -> allowedPkgs = allowedPkgs + pkg
            null -> Unit
        }
    }

    // ---- Learning / UI ----
    var autoLearn: Boolean
        get() = bool("auto_learn", true)
        set(v) = putBool("auto_learn", v)

    /** Learn app screens while the owner uses the phone (structure only, on-device). Off by default. */
    var passiveLearn: Boolean
        get() = bool("passive_learn", false)
        set(v) = putBool("passive_learn", v)

    /** "Teach me by hand": save the skill by itself once the owner is back on the home screen. */
    var autoSaveTaught: Boolean
        get() = bool("auto_save_taught", true)
        set(v) = putBool("auto_save_taught", v)

    var showBubble: Boolean
        get() = bool("bubble", true)
        set(v) = putBool("bubble", v)

    // ---- Trading mode (a paper account inside the app; never real money) ----
    /** The agent runs a cycle by itself every [tradeEveryMin] minutes. */
    var tradeAuto: Boolean
        get() = bool("trade_auto", false)
        set(v) = putBool("trade_auto", v)
    var tradeEveryMin: Int
        get() = p.getInt("trade_every", 60)
        set(v) = p.edit().putInt("trade_every", v.coerceIn(15, 1440)).apply()
    var tradeSymbols: String
        get() = str("trade_symbols", "BTCUSDT, ETHUSDT")
        set(v) = putStr("trade_symbols", v)
    /** Candle size the decisions are made on: 15m, 1h or 4h. */
    var tradeInterval: String
        get() = str("trade_interval", "1h")
        set(v) = putStr("trade_interval", v)
    var tradeNews: Boolean
        get() = bool("trade_news", true)
        set(v) = putBool("trade_news", v)
    /** Decide with the stronger model (dearer, one request per cycle either way). */
    var tradeStrong: Boolean
        get() = bool("trade_strong", false)
        set(v) = putBool("trade_strong", v)
    var tradeStart: Float
        get() = float("trade_start", 10_000f)
        set(v) = putFloat("trade_start", v.coerceIn(100f, 10_000_000f))
    var tradeMaxPosition: Float
        get() = float("trade_max_pos", 2_000f)
        set(v) = putFloat("trade_max_pos", v.coerceAtLeast(10f))
    var tradeRiskPct: Float
        get() = float("trade_risk", 1f)
        set(v) = putFloat("trade_risk", v.coerceIn(0.1f, 5f))
    var tradeMaxSlPct: Float
        get() = float("trade_max_sl", 3f)
        set(v) = putFloat("trade_max_sl", v.coerceIn(0.2f, 15f))
    var tradeMaxPerDay: Int
        get() = p.getInt("trade_per_day", 6)
        set(v) = p.edit().putInt("trade_per_day", v.coerceIn(1, 50)).apply()
    var tradeDailyLossPct: Float
        get() = float("trade_day_loss", 3f)
        set(v) = putFloat("trade_day_loss", v.coerceIn(0.5f, 50f))
    var tradeMinConfidence: Int
        get() = p.getInt("trade_min_conf", 60)
        set(v) = p.edit().putInt("trade_min_conf", v.coerceIn(0, 100)).apply()
    var tradeShort: Boolean
        get() = bool("trade_short", true)
        set(v) = putBool("trade_short", v)

    fun isStrong(model: String) = model == strongModel && model != fastModel

    fun cost(model: String, u: Usage): Double {
        val strong = isStrong(model)
        val inP = (if (strong) strongIn else fastIn).toDouble()
        val outP = (if (strong) strongOut else fastOut).toDouble()
        return (u.input * inP + u.cacheWrite * inP * 1.25 + u.cacheRead * inP * 0.1 + u.output * outP) / 1_000_000.0
    }

    private fun dayKey() = "spend_" + LocalDate.now().toString()
    fun todaySpend(): Double = p.getFloat(dayKey(), 0f).toDouble()
    fun addSpend(v: Double) {
        if (v > 0) p.edit().putFloat(dayKey(), (todaySpend() + v).toFloat()).apply()
    }

    companion object {
        const val DEFAULT_BLOCKED =
            "bank, wallet, pay, qnb, qib, cbq, alrayan, masraf, dukhan, dohabank, ahlibank, paypal, " +
                "authenticator, authy, 1password, bitwarden, lastpass, keepass, metamask, trustwallet, ledger, packageinstaller"
        const val DEFAULT_READ_ONLY =
            "binance, mexc, bybit, okx, okex, kucoin, bitget, gateio, huobi, htx, kraken, coinbase, crypto, " +
                "exness, etoro, metatrader, tradingview, trading, forex, broker, invest"
    }
}
