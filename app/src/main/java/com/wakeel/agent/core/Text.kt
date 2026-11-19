package com.wakeel.agent.core

import java.util.Locale

object Text {
    // Diacritics and tatweel, plus the invisible direction marks Arabic interfaces put around mixed text
    private val tashkeel = Regex("[\\u064B-\\u0652\\u0670\\u0640\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
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
                    '\u2010', '\u2011', '\u2012', '\u2013' -> '-'   // "Wi‑Fi" is written with a non-breaking hyphen
                    '\u00A0', '\u202F' -> ' '
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
