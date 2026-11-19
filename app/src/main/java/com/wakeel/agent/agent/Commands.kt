package com.wakeel.agent.agent

import com.wakeel.agent.core.Text
import java.net.URLEncoder

/** A command understood locally — executed with Android APIs, never sent to the model. */
sealed interface Cmd {
    data class Global(val action: String, val say: String) : Cmd
    data class Info(val kind: String) : Cmd
    data object Health : Cmd
    data class Torch(val on: Boolean) : Cmd
    data class Volume(val dir: Int) : Cmd
    data class Media(val key: String) : Cmd
    data class Url(val url: String, val say: String) : Cmd
    data class Alarm(val hour: Int, val minute: Int) : Cmd
    data class Timer(val seconds: Int, val label: String) : Cmd
    data class SettingsPage(val key: String) : Cmd
    data class Toggle(val key: String, val on: Boolean) : Cmd
}

/**
 * Pure parser for everyday Arabic/English phone commands (unit-tested on CI).
 * Everything is matched on normalized text (see [Text.norm]); queries keep the owner's original spelling.
 */
object Commands {
    private fun set(vararg s: String): Set<String> = s.map { Text.norm(it) }.toSet()
    private fun map(vararg p: Pair<String, String>): Map<String, String> = p.associate { Text.norm(it.first) to it.second }
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    val OPEN_VERBS = set("افتح", "شغل", "افتحي", "روح", "ادخل", "اذهب", "open", "launch", "start", "go")
    private val ON_VERBS = set("شغل", "فعل", "ولع", "اشعل", "enable")
    private val OFF_VERBS = set("اطفئ", "أطفئ", "طفي", "عطل", "اغلق", "أغلق", "سكر", "اوقف", "أوقف", "disable")
    private val SEND_VERBS = set("ارسل", "أرسل", "ابعث", "اكتب", "راسل", "كلم", "send", "افتح")
    private val SETTINGS_WORDS = set("إعدادات", "الإعدادات", "ضبط", "الضبط", "settings", "setting")
    private val SMALL = set("صفحة", "قائمة", "إلى", "على", "في", "ل", "لي", "the", "to", "of", "page")
    private val TOGGLEABLE = setOf("wifi", "bluetooth", "location", "nfc", "dnd", "battery_saver", "airplane", "hotspot")

    private val NAV: List<Triple<Set<String>, String, String>> = listOf(
        Triple(set("ارجع", "رجوع", "للخلف", "رجعني", "back"), "back", "تم الرجوع"),
        Triple(set("الرئيسية", "الشاشة الرئيسية", "اذهب للرئيسية", "روح للرئيسية", "home"), "home", "تم الذهاب للشاشة الرئيسية"),
        Triple(set("الإشعارات", "افتح الإشعارات", "اعرض الإشعارات", "شريط الإشعارات", "افتح شريط الإشعارات", "notifications"), "notifications", "تم فتح الإشعارات"),
        Triple(set("التطبيقات الأخيرة", "التطبيقات المفتوحة", "recents"), "recents", "تم عرض التطبيقات الأخيرة"),
        Triple(set("الإعدادات السريعة", "افتح الإعدادات السريعة", "اللوحة السريعة", "لوحة التحكم", "quick settings"), "quick_settings", "تم فتح الإعدادات السريعة"),
        Triple(set("لقطة شاشة", "صور الشاشة", "خذ لقطة شاشة", "سكرين شوت", "screenshot"), "screenshot", "تم أخذ لقطة شاشة"),
        Triple(set("اقفل الشاشة", "قفل الشاشة", "اقفل الهاتف", "lock"), "lock", "تم قفل الشاشة"),
        Triple(set("قائمة الطاقة", "أطفئ الهاتف", "اطفئ الجهاز", "أعد تشغيل الهاتف", "إعادة التشغيل", "إعادة تشغيل الهاتف", "power menu"), "power", "فُتحت قائمة الطاقة — اختر ما تريد"),
    )

    private val Q_WORDS = set("كم", "نسبة", "مستوى", "حالة", "هل", "كيف")
    private val W_BATTERY = set("البطارية", "بطارية", "الشحن", "بطاريتي")
    private val W_STORAGE = set("المساحة", "مساحة", "التخزين", "تخزين")
    private val W_RAM = set("الرام", "رام", "ram")
    private val W_NET = set("النت", "الإنترنت", "الشبكة", "الاتصال", "الواي", "internet")
    private val INFO_DEVICE = set("معلومات الهاتف", "معلومات الجهاز", "مواصفات الهاتف", "مواصفات الجهاز", "ما هو هاتفي", "device info")

    private val HEALTH_VERBS = set("شخص", "افحص", "فحص", "تشخيص", "حلل")
    private val PHONE_WORDS = set("الهاتف", "الجهاز", "الجوال", "هاتفي", "جهازي", "جوالي", "الموبايل", "التلفون")
    private val HEALTH_EXACT = set("صحة الجهاز", "صحة الهاتف", "حالة الهاتف", "حالة الجهاز", "فحص شامل", "تشخيص")

    private val W_TORCH = set("الكشاف", "كشاف", "الفلاش", "فلاش", "المصباح", "flashlight", "torch")
    private val VOL_UP = set("ارفع الصوت", "علي الصوت", "زود الصوت", "ارفع مستوى الصوت", "volume up")
    private val VOL_DOWN = set("اخفض الصوت", "خفض الصوت", "وطي الصوت", "نزل الصوت", "قصر الصوت", "اخفض مستوى الصوت", "volume down")
    private val VOL_MUTE = set("اكتم الصوت", "كتم الصوت", "صامت", "الوضع الصامت", "mute")
    private val MEDIA = map(
        "أوقف الموسيقى" to "pause", "اوقف الأغنية" to "pause", "إيقاف مؤقت" to "pause", "وقف الموسيقى" to "pause", "أوقف التشغيل" to "pause", "pause" to "pause",
        "شغل الموسيقى" to "play", "كمل الموسيقى" to "play", "كمل التشغيل" to "play", "تشغيل الموسيقى" to "play", "play" to "play",
        "التالي" to "next", "الأغنية التالية" to "next", "المقطع التالي" to "next", "next" to "next",
        "السابق" to "prev", "الأغنية السابقة" to "prev", "المقطع السابق" to "prev", "previous" to "prev",
    )

    private val SEARCH_VERBS = set("ابحث", "بحث", "دور", "دورلي", "search", "find")
    private val FILLERS = set("عن", "في", "على", "ب", "من", "لي", "for", "on", "in", "the")
    private val VERT1 = map(
        "يوتيوب" to "youtube", "اليوتيوب" to "youtube", "بيوتيوب" to "youtube", "باليوتيوب" to "youtube", "youtube" to "youtube",
        "جوجل" to "web", "قوقل" to "web", "غوغل" to "web", "بجوجل" to "web", "بقوقل" to "web", "google" to "web", "الإنترنت" to "web", "النت" to "web", "الويب" to "web",
        "الخرائط" to "maps", "خرائط" to "maps", "بالخرائط" to "maps", "الخريطة" to "maps", "maps" to "maps",
        "المتجر" to "store", "بالمتجر" to "store", "store" to "store",
    )
    private val VERT2 = map(
        "بلاي ستور" to "store", "متجر بلاي" to "store", "جوجل بلاي" to "store", "قوقل بلاي" to "store", "play store" to "store",
        "قوقل ماب" to "maps", "جوجل ماب" to "maps", "خرائط جوجل" to "maps", "خرائط قوقل" to "maps", "google maps" to "maps",
    )
    private val NAV_VERBS = set("وجهني", "خذني", "وصلني", "اتجاهات", "navigate")
    private val NAV_FILL = set("إلى", "ل", "على", "نحو", "to")
    private val W_WHATSAPP = set("واتساب", "واتس", "الواتساب", "الواتس", "بالواتساب", "بواتساب", "whatsapp")
    private val W_SMS = set("نصية", "sms")
    private val W_MAIL = set("إيميل", "ايميل", "الإيميل", "بريد", "بريدا", "email", "mail")

    private val PHONE_RE = Regex("\\+?[0-9٠-٩][0-9٠-٩ \\-]{6,}[0-9٠-٩]")
    private val MAIL_RE = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+")
    private val URL_RE = Regex("https?://\\S+")
    private val DOMAIN_RE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}(/\\S*)?$")

    private fun after(raw: String, end: Int): String =
        raw.substring(end.coerceAtMost(raw.length)).trim().trimStart(':', '،', ',', '-', ' ')
            .removePrefix("نصها").removePrefix("وقل").removePrefix("تقول").trim().trimStart(':', ' ')

    fun parse(task: String): Cmd? {
        val raw = task.trim().trimEnd('.', '!', '؟', '?', ' ')
        if (raw.isEmpty()) return null
        val orig = raw.split(Regex("\\s+"))
        val nw = orig.map { Text.norm(it) }
        val tok = nw.map { it.trim(',', ':', ';', '.', '?', '!') }
        val t = tok.joinToString(" ")
        val n = tok.size

        // --- global navigation
        NAV.firstOrNull { t in it.first }?.let { return Cmd.Global(it.second, it.third) }

        // --- quick facts and health
        if (n <= 6) {
            val asks = tok.any { it in Q_WORDS }
            if (asks && tok.any { it in W_BATTERY }) return Cmd.Info("battery")
            if (asks && tok.any { it in W_STORAGE }) return Cmd.Info("storage")
            if (asks && (tok.any { it in W_RAM } || t.contains(Text.norm("الذاكرة العشوائية")))) return Cmd.Info("ram")
            if (asks && tok.any { it in W_NET } && tok.none { it in SETTINGS_WORDS }) return Cmd.Info("network")
            if (t in INFO_DEVICE) return Cmd.Info("device")
        }
        if (t in HEALTH_EXACT || (n <= 5 && tok[0] in HEALTH_VERBS && tok.any { it in PHONE_WORDS })) return Cmd.Health

        // --- torch, volume, media
        if (n <= 4 && tok.any { it in W_TORCH }) {
            if (tok.any { it in OFF_VERBS || it == "off" }) return Cmd.Torch(false)
            if (tok.any { it in ON_VERBS || it in OPEN_VERBS || it == "on" }) return Cmd.Torch(true)
        }
        if (t in VOL_UP) return Cmd.Volume(1)
        if (t in VOL_DOWN) return Cmd.Volume(-1)
        if (t in VOL_MUTE) return Cmd.Volume(0)
        MEDIA[t]?.let { return Cmd.Media(it) }

        // --- plain links
        URL_RE.find(raw)?.let { m ->
            val rest = Text.norm(raw.replace(m.value, " ")).split(' ').filter { it.isNotBlank() }
            if (rest.all { it in OPEN_VERBS || it in set("الرابط", "رابط", "هذا", "الموقع", "موقع") }) return Cmd.Url(m.value, "تم فتح الرابط")
        }
        if (n in 1..3 && DOMAIN_RE.matches(tok.last()) && tok.dropLast(1).all { it in OPEN_VERBS || it in set("موقع", "الموقع") }) {
            return Cmd.Url("https://" + tok.last(), "تم فتح الموقع")
        }

        // --- messages to a number / address (prepared, never sent)
        val phone = PHONE_RE.find(raw)
        if (phone != null && tok[0] in SEND_VERBS) {
            val number = Text.norm(phone.value).filter { it.isDigit() || it == '+' }
            val msg = after(raw, phone.range.last + 1)
            if (tok.any { it in W_WHATSAPP }) {
                val intl = number.removePrefix("+").removePrefix("00")
                val url = "https://wa.me/$intl" + if (msg.isNotEmpty()) "?text=" + enc(msg) else ""
                return Cmd.Url(url, "فُتحت محادثة واتساب مع $number" + if (msg.isNotEmpty()) " والرسالة جاهزة — اضغط إرسال" else "")
            }
            if (tok.any { it in W_SMS }) {
                val url = "sms:$number" + if (msg.isNotEmpty()) "?body=" + enc(msg) else ""
                return Cmd.Url(url, "فُتحت رسالة نصية إلى $number — راجعها ثم اضغط إرسال")
            }
        }
        val mail = MAIL_RE.find(raw)
        if (mail != null && tok[0] in SEND_VERBS && tok.any { it in W_MAIL }) {
            val body = after(raw, mail.range.last + 1)
            val url = "mailto:" + mail.value + if (body.isNotEmpty()) "?body=" + enc(body) else ""
            return Cmd.Url(url, "فُتحت مسودة بريد إلى ${mail.value} — راجعها ثم أرسل")
        }
        Regex("^(اتصل|كلم|call)\\s*(ب|علي|to)?\\s*(الرقم\\s*)?(\\+?[0-9][0-9 \\-]{5,})$").find(t)?.let { m ->
            val num = m.groupValues[4].replace(" ", "").replace("-", "")
            return Cmd.Url("tel:$num", "فُتحت لوحة الاتصال بالرقم $num — اضغط اتصال للتأكيد")
        }

        // --- alarm and timer
        if (n <= 9 && Regex("(منبه|نبهني|صحيني|alarm)").containsMatchIn(t)) {
            Regex("(\\d{1,2})(?:[:.](\\d{2}))?").find(t)?.let { m ->
                var hour = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val pm = Regex("(مساء|مسا|ليلا|بالليل|العصر|المغرب|العشاء)").containsMatchIn(t) || tok.any { it == "م" || it == "pm" }
                val am = Regex("(صباحا|الصبح|الفجر)").containsMatchIn(t) || tok.any { it == "ص" || it == "am" }
                if (pm && hour < 12) hour += 12
                if (am && hour == 12) hour = 0
                if (hour in 0..23 && min in 0..59) return Cmd.Alarm(hour, min)
            }
        }
        if (n <= 8 && Regex("(موقت|تايمر|timer)").containsMatchIn(t)) {
            Regex("(\\d+)\\s*(ثانيه|ثواني|ثوان|sec|دقيقه|دقايق|min|ساعه|ساعات|hour)").find(t)?.let { m ->
                val v = m.groupValues[1].toInt()
                val unit = m.groupValues[2]
                val secs = when {
                    unit.startsWith("ث") || unit == "sec" -> v
                    unit.startsWith("س") || unit == "hour" -> v * 3600
                    else -> v * 60
                }
                if (secs > 0) return Cmd.Timer(secs, m.value)
            }
        }

        // --- searches that open results directly
        if (n >= 3 && tok[0] in SEARCH_VERBS) {
            var i = 1
            var end = n
            var vertical: String? = null
            while (i < end) {
                val bi = if (i + 1 < end) tok[i] + " " + tok[i + 1] else ""
                when {
                    bi in VERT2 -> { vertical = VERT2[bi]; i += 2 }
                    tok[i] in VERT1 -> { vertical = VERT1[tok[i]]; i++ }
                    tok[i] in FILLERS -> i++
                    else -> break
                }
            }
            if (vertical == null && end - i >= 2) {
                val bi = tok[end - 2] + " " + tok[end - 1]
                if (bi in VERT2 && end - i >= 3) { vertical = VERT2[bi]; end -= 2 }
                else if (tok[end - 1] in VERT1) { vertical = VERT1[tok[end - 1]]; end -= 1 }
                if (vertical != null && end - 1 > i && tok[end - 1] in FILLERS) end -= 1
            }
            val query = if (i < end) orig.subList(i, end).joinToString(" ") else ""
            if (vertical != null && query.isNotBlank()) {
                return when (vertical) {
                    "youtube" -> Cmd.Url("https://www.youtube.com/results?search_query=" + enc(query), "عرضتُ نتائج «$query» في يوتيوب")
                    "maps" -> Cmd.Url("geo:0,0?q=" + enc(query), "بحثتُ عن «$query» في الخرائط")
                    "store" -> Cmd.Url("market://search?q=" + enc(query), "بحثتُ عن «$query» في المتجر")
                    else -> Cmd.Url("https://www.google.com/search?q=" + enc(query), "بحثتُ في جوجل عن «$query»")
                }
            }
        }
        if (n >= 2 && tok[0] in NAV_VERBS) {
            var i = 1
            while (i < n && tok[i] in NAV_FILL) i++
            val place = orig.drop(i).joinToString(" ")
            if (place.isNotBlank()) return Cmd.Url("google.navigation:q=" + enc(place), "بدأتُ الملاحة إلى «$place»")
        }

        // --- settings: switches and pages
        if (n in 2..5 && (tok[0] in ON_VERBS || tok[0] in OFF_VERBS)) {
            val full = tok.drop(1).joinToString(" ")
            val trimmed = tok.drop(1).filter { it !in SMALL }.joinToString(" ")
            val key = SettingsCatalog.keyForWords(full) ?: SettingsCatalog.keyForWords(trimmed)
            if (key != null && key in TOGGLEABLE) return Cmd.Toggle(key, tok[0] in ON_VERBS)
        }
        if (n <= 6) {
            val hasSettings = tok.any { it in SETTINGS_WORDS }
            val hasOpen = tok[0] in OPEN_VERBS
            val rest = tok.filter { it !in OPEN_VERBS && it !in SETTINGS_WORDS && it !in SMALL }.joinToString(" ")
            if (rest.isEmpty() && hasSettings) return Cmd.SettingsPage("main")
            val key = if (rest.isEmpty()) null else SettingsCatalog.keyForWords(rest)
            if (key != null && (hasSettings || (hasOpen && key !in SettingsCatalog.ambiguous))) return Cmd.SettingsPage(key)
        }
        return null
    }
}
