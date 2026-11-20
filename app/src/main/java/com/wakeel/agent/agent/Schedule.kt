package com.wakeel.agent.agent

import com.wakeel.agent.core.Text
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Finds a time in a task — "بعد 10 دقائق", "الساعة 8 مساءً", "غداً الساعة 7", "in 5 minutes" — so the task
 * can wait for it on the phone. The model cannot wait: handed "after a minute", it acts at once.
 *
 * Only the instruction is searched. Text after a colon, or inside quotes or brackets, is the content of
 * a message ("أرسل له: سأصل بعد 5 دقائق") and is left alone.
 */
object Schedule {
    class Plan(
        /** The task without its time phrase: what runs when the time comes. */
        val task: String,
        val at: Long,
        val phrase: String,
    )

    const val MIN_DELAY_MS = 3_000L
    const val MAX_DELAY_MS = 7 * 24 * 3600_000L

    /** Alarms, timers and reminders carry a time of their own: they are done now, by the clock app. */
    private val NOT_A_SCHEDULE = Regex("(منبه|نبهني|صحيني|موقت|تايمر|ذكرني|تذكير|alarm|timer|remind|stopwatch)")

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private const val UNITS = "ثاني[ةه]|ثانيتين|ثواني|ثوان|دقيق[ةه]|دقيقتين|دقائق|دقايق|ساع[ةه]|ساعتين|ساعات"
    private const val WORDS = "واحد[ةه]?|[اإ]ثنين|اثنتين|ثلاث[ةه]?|[أا]ربع[ةه]?|خمس[ةه]?|ست[ةه]?|سبع[ةه]?|ثماني[ةه]?|ثمان|تسع[ةه]?|عشر[ةه]?|" +
        "عشرين|ثلاثين|[أا]ربعين|خمسين|ستين|نصف|ربع|ثلث"
    private const val DAY = "اليوم|غد[اً]*|بكر[ةهاى]"
    private const val ORD = "الحادي[ةه]\\s+عشر[ةه]?|الثاني[ةه]\\s+عشر[ةه]?|الواحد[ةه]|الثاني[ةه]|الثالث[ةه]|الرابع[ةه]|الخامس[ةه]|" +
        "السادس[ةه]|السابع[ةه]|الثامن[ةه]|التاسع[ةه]|العاشر[ةه]"
    private const val PART = "بعد\\s+الظهر|ص|م|صباح[اً]*|الصبح|الصباح|مساء[اً]*|المساء|ظهر[اً]*|الظهر|عصر[اً]*|العصر|ليل[اً]*|الليل|بالليل|" +
        "فجر[اً]*|الفجر|am|pm|a\\.m\\.?|p\\.m\\.?"

    // بعد [مرور] [10 | عشر | نصف] دقائق [ونصف] [من الآن]
    private val RELATIVE = Regex(
        B + "بعد\\s+(?:مرور\\s+)?(?:(?:حوالي|تقريب[اً]*)\\s+)?(?:(\\d{1,3})\\s*|(" + WORDS + ")\\s+)?(" + UNITS + ")" + E +
            "(?:\\s*و\\s*(نصف|ربع|ثلث)" + E + ")?(?:\\s+من\\s+ال[اآ]ن" + E + ")?"
    )
    private val RELATIVE_EN = Regex(
        "(?i)\\b(?:in|after)\\s+(?:(\\d{1,3})\\s*|(an?|one|two|three|five|ten|half\\s+an?)\\s+)(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b(?:\\s+from\\s+now\\b)?"
    )

    // [غداً] [في] [تمام] الساعة 8[:30] [والنصف] [إلا ربعاً] [مساءً] [غداً]
    private val ABSOLUTE = Regex(
        "(?i)" + B + "(?:(" + DAY + ")\\s+)?(?:(?:في|عند|على)\\s+)?(?:تمام\\s+)?الساع[ةه]\\s+(\\d{1,2}(?!\\d)|(?:" + ORD + ")" + E + ")" +
            "(?:\\s*[:.]\\s*(\\d{2})(?!\\d))?" +
            "(?:\\s*و\\s*(?:ال)?(نصف|ربع|ثلث)" + E + ")?" +
            "(?:\\s+[إا]لا\\s+(?:ال)?(ربع|ثلث)[اً]*" + E + ")?" +
            "(?:\\s*(" + PART + ")" + E + ")?" +
            "(?:\\s+(" + DAY + ")" + E + ")?"
    )
    private val ABSOLUTE_EN = Regex(
        "(?i)\\b(?:(today|tomorrow)\\s+)?at\\s+(\\d{1,2})(?!\\d)(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?(?![\\p{L}\\p{N}])(?:\\s+(today|tomorrow)\\b)?"
    )

    private val SPACES = Regex("\\s+")
    private val SPACE_BEFORE_MARK = Regex("\\s+([،,.؛])")

    fun parse(raw: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Plan? {
        if (raw.length < 6 || NOT_A_SCHEDULE.containsMatchIn(Text.norm(raw))) return null
        val body = instruction(asciiDigits(raw))   // same length as raw, so a match's range is a range in raw

        var at: Long? = null
        var range: IntRange? = null
        val rel = RELATIVE.find(body)
        val relEn = if (rel == null) RELATIVE_EN.find(body) else null
        if (rel != null) {
            val unit = Text.norm(rel.groupValues[3])
            val per = unitMs(unit)
            val count = rel.groups[1]?.value?.toDouble() ?: rel.groups[2]?.value?.let { wordNumber(it) } ?: if (unit.endsWith("تين")) 2.0 else 1.0
            val extra = rel.groups[4]?.value?.let { wordNumber(it) } ?: 0.0
            at = now + ((count + extra) * per).toLong()
            range = rel.range
        } else if (relEn != null) {
            val u = relEn.groupValues[3].lowercase(Locale.ROOT)
            val per = when { u.startsWith("s") -> 1000L; u.startsWith("m") -> 60_000L; else -> 3600_000L }
            val count = relEn.groups[1]?.value?.toDouble() ?: when (relEn.groupValues[2].lowercase(Locale.ROOT).substringBefore(' ')) {
                "two" -> 2.0; "three" -> 3.0; "five" -> 5.0; "ten" -> 10.0; "half" -> 0.5; else -> 1.0
            }
            at = now + (count * per).toLong()
            range = relEn.range
        } else {
            val abs = ABSOLUTE.find(body)
            val absEn = if (abs == null) ABSOLUTE_EN.find(body) else null
            if (abs != null) {
                var hour = hourOf(abs.groupValues[2]) ?: return null
                var minute = abs.groups[3]?.value?.toInt() ?: 0
                abs.groups[4]?.value?.let { minute = Math.round(wordNumber(it) * 60).toInt() }   // والنصف
                abs.groups[5]?.value?.let {                                                      // إلا ربعاً
                    minute = 60 - Math.round(wordNumber(it) * 60).toInt()
                    hour = if (hour == 0) 23 else if (hour == 1) 12 else hour - 1
                }
                at = resolve(hour, minute, abs.groups[6]?.value, abs.groups[1]?.value ?: abs.groups[7]?.value, now, zone)
                range = abs.range
            } else if (absEn != null) {
                at = resolve(
                    absEn.groupValues[2].toInt(), absEn.groups[3]?.value?.toInt() ?: 0, absEn.groups[4]?.value,
                    absEn.groups[1]?.value ?: absEn.groups[5]?.value, now, zone,
                )
                range = absEn.range
            }
        }

        val time = at ?: return null
        val r = range ?: return null
        if (time - now < MIN_DELAY_MS || time - now > MAX_DELAY_MS) return null
        val task = (raw.substring(0, r.first) + " " + raw.substring(r.last + 1))
            .replace(SPACES, " ").replace(SPACE_BEFORE_MARK, "\$1").trim()
            .trim(' ', '،', ',', '.', '؛', '-', '–', '—').trim()
        if (task.length < 3) return null
        return Plan(task, time, raw.substring(r).trim())
    }

    /** "3:05 م" */
    fun clock(at: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = at }
        val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return String.format(Locale.US, "%d:%02d %s", h, c.get(Calendar.MINUTE), if (c.get(Calendar.AM_PM) == Calendar.AM) "ص" else "م")
    }

    /** How the owner is told when: "بعد 10 دقيقة (الساعة 3:20 ص)", "اليوم الساعة 8:00 م", "غداً الساعة 7:00 ص". */
    fun describe(at: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val d = at - now
        val t = clock(at, zone)
        if (d < 55_000) return "بعد ${maxOf(1L, Math.round(d / 1000.0))} ثانية"
        if (d < 90_000) return "بعد دقيقة (الساعة $t)"
        if (d < 3600_000) return "بعد ${Math.round(d / 60_000.0)} دقيقة (الساعة $t)"
        val day = Calendar.getInstance(zone).apply { timeInMillis = now }
        val that = Calendar.getInstance(zone).apply { timeInMillis = at }
        fun same() = day.get(Calendar.YEAR) == that.get(Calendar.YEAR) && day.get(Calendar.DAY_OF_YEAR) == that.get(Calendar.DAY_OF_YEAR)
        if (same()) return "اليوم الساعة $t"
        day.add(Calendar.DAY_OF_YEAR, 1)
        if (same()) return "غداً الساعة $t"
        return "يوم ${that.get(Calendar.DAY_OF_MONTH)}/${that.get(Calendar.MONTH) + 1} الساعة $t"
    }

    // ------------------------------------------------------------------ parts

    private fun asciiDigits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
        )
    }

    /** The instruction part: quoted or bracketed text, and everything after a colon, is blanked out (same length). */
    private fun instruction(s: String): String {
        val out = StringBuilder(s)
        val closing = mapOf('«' to '»', '“' to '”', '"' to '"', '(' to ')', '[' to ']', '{' to '}')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val close = closing[c]
            if (close != null) {
                val j = s.indexOf(close, i + 1)
                val end = if (j < 0) s.length - 1 else j
                for (k in i..end) out.setCharAt(k, ' ')
                i = end + 1
                continue
            }
            if (c == ':' || c == '：') {
                val inClock = i > 0 && s[i - 1].isDigit() && i + 1 < s.length && s[i + 1].isDigit()
                if (!inClock) {
                    for (k in i until s.length) out.setCharAt(k, ' ')
                    break
                }
            }
            i++
        }
        return out.toString()
    }

    private fun unitMs(unit: String): Long = when {
        unit.startsWith("ثان") || unit.startsWith("ثوان") -> 1000L
        unit.startsWith("دق") -> 60_000L
        else -> 3600_000L
    }

    private fun wordNumber(w: String): Double = when (Text.norm(w)) {
        "واحد", "واحده" -> 1.0
        "اثنين", "اثنتين" -> 2.0
        "ثلاث", "ثلاثه" -> 3.0
        "اربع", "اربعه" -> 4.0
        "خمس", "خمسه" -> 5.0
        "ست", "سته" -> 6.0
        "سبع", "سبعه" -> 7.0
        "ثمان", "ثماني", "ثمانيه" -> 8.0
        "تسع", "تسعه" -> 9.0
        "عشر", "عشره" -> 10.0
        "عشرين" -> 20.0
        "ثلاثين" -> 30.0
        "اربعين" -> 40.0
        "خمسين" -> 50.0
        "ستين" -> 60.0
        "نصف" -> 0.5
        "ربع" -> 0.25
        "ثلث" -> 1.0 / 3
        else -> 1.0
    }

    private fun hourOf(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val n = Text.norm(s)
        return when {
            n.startsWith("الحادي") -> 11
            n.startsWith("الثاني") && n.contains("عشر") -> 12
            n.startsWith("الواحد") -> 1
            n.startsWith("الثاني") -> 2
            n.startsWith("الثالث") -> 3
            n.startsWith("الرابع") -> 4
            n.startsWith("الخامس") -> 5
            n.startsWith("السادس") -> 6
            n.startsWith("السابع") -> 7
            n.startsWith("الثامن") -> 8
            n.startsWith("التاسع") -> 9
            n.startsWith("العاشر") -> 10
            else -> null
        }
    }

    /**
     * Turns "8", "8 مساءً", "غداً 7" into a moment. Without صباحاً/مساءً the next time the clock shows that hour
     * is meant; for tomorrow, 1–6 is taken as the afternoon and 7–11 as the morning, as people say it.
     */
    private fun resolve(hourRaw: Int, minute: Int, part: String?, day: String?, now: Long, zone: TimeZone): Long? {
        if (hourRaw !in 0..23 || minute !in 0..59) return null
        val p = part?.let { Text.norm(it).replace(".", "") }
        val d = day?.let { Text.norm(it) }
        val tomorrow = d != null && (d.startsWith("غد") || d.startsWith("بكر") || d == "tomorrow")
        fun at(dayOffset: Int, hour: Int): Long = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val h12 = hourRaw % 12
        val fixed: Int? = when {
            hourRaw > 12 || hourRaw == 0 -> hourRaw
            p == null -> null
            p == "ص" || p == "am" || p.contains("صبح") || p.contains("صباح") || p.contains("فجر") -> h12
            p.contains("ليل") -> if (hourRaw == 12 || hourRaw <= 4) h12 else h12 + 12
            p.contains("ظهر") && !p.startsWith("بعد") && hourRaw in 10..11 -> hourRaw
            else -> h12 + 12
        }
        if (fixed != null) {
            if (tomorrow) return at(1, fixed)
            val t = at(0, fixed)
            return if (t > now + 1000) t else at(1, fixed)
        }
        if (tomorrow) return at(1, if (hourRaw in 1..6) hourRaw + 12 else hourRaw)
        return listOf(at(0, h12), at(0, h12 + 12), at(1, h12), at(1, h12 + 12)).filter { it > now + 1000 }.minOrNull()
    }
}
