package com.wakeel.agent.core

import java.util.Locale

object Text {
    private val tashkeel = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val spaces = Regex("\\s+")

    /** Normalizes Arabic/English text for matching: lowercase, no diacritics, unified letters and digits. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase(Locale.ROOT)) {
            sb.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '،' -> ','
                    else -> ch
                }
            )
        }
        return sb.toString().replace(tashkeel, "").replace(spaces, " ").trim()
    }

    fun money(v: Double): String = when {
        v == 0.0 -> "$0"
        v < 0.01 -> "$" + String.format(Locale.US, "%.4f", v)
        else -> "$" + String.format(Locale.US, "%.3f", v)
    }

    fun tokens(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}
