package com.wakeel.agent.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.util.Locale

/** Live facts about the phone, read locally (zero API cost). */
data class DeviceSnapshot(
    val batteryPct: Int,
    val charging: Boolean,
    val batteryTempC: Float,
    val batteryHealthOk: Boolean,
    val storageTotal: Long,
    val storageFree: Long,
    val ramTotal: Long,
    val ramAvail: Long,
    val lowMemory: Boolean,
    val network: String,
    val online: Boolean,
    val metered: Boolean,
    val downMbps: Int,
    val model: String,
    val android: String,
    val uptimeHours: Long,
) {
    val storageUsedPct: Int get() = if (storageTotal > 0) (((storageTotal - storageFree) * 100) / storageTotal).toInt() else 0
    val ramUsedPct: Int get() = if (ramTotal > 0) (((ramTotal - ramAvail) * 100) / ramTotal).toInt() else 0
}

object DeviceInfo {

    fun gb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)

    fun snapshot(ctx: Context): DeviceSnapshot {
        // Battery
        val bi: Intent? = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = bi?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD) ?: BatteryManager.BATTERY_HEALTH_GOOD

        // Storage
        var total = 0L
        var free = 0L
        runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            total = fs.totalBytes
            free = fs.availableBytes
        }

        // Memory
        val mi = ActivityManager.MemoryInfo()
        runCatching { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi) }

        // Network
        var net = "none"
        var online = false
        var metered = false
        var down = 0
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null) {
                net = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                down = caps.linkDownstreamBandwidthKbps / 1000
            }
        }

        return DeviceSnapshot(
            batteryPct = pct, charging = charging, batteryTempC = temp,
            batteryHealthOk = health == BatteryManager.BATTERY_HEALTH_GOOD,
            storageTotal = total, storageFree = free,
            ramTotal = mi.totalMem, ramAvail = mi.availMem, lowMemory = mi.lowMemory,
            network = net, online = online, metered = metered, downMbps = down,
            model = (Build.MANUFACTURER + " " + Build.MODEL).trim(),
            android = Build.VERSION.RELEASE ?: "",
            uptimeHours = SystemClock.elapsedRealtime() / 3_600_000L,
        )
    }

    /** Compact English facts for the model. */
    fun brief(s: DeviceSnapshot): String = buildString {
        append("model=").append(s.model).append(", android=").append(s.android)
        append("; battery=").append(s.batteryPct).append('%').append(if (s.charging) " charging" else "")
        append(", temp=").append(String.format(Locale.US, "%.0f", s.batteryTempC)).append("C")
        append(", health=").append(if (s.batteryHealthOk) "good" else "degraded")
        append("; storage used ").append(s.storageUsedPct).append("% (free ").append(gb(s.storageFree)).append(" of ").append(gb(s.storageTotal)).append(" GB)")
        append("; RAM used ").append(s.ramUsedPct).append("% (free ").append(gb(s.ramAvail)).append(" of ").append(gb(s.ramTotal)).append(" GB)")
        append("; network=").append(s.network).append(if (s.online) " online" else " NOT validated")
        if (s.downMbps > 0) append(" ~").append(s.downMbps).append("Mbps")
        append("; uptime=").append(s.uptimeHours).append("h")
    }

    fun networkAr(s: DeviceSnapshot): String = when (s.network) {
        "wifi" -> "واي فاي"
        "mobile" -> "بيانات الجوال"
        "ethernet" -> "إيثرنت"
        "vpn" -> "VPN"
        "none" -> "غير متصل"
        else -> "متصل"
    }

    /** Short Arabic answer to a direct question, e.g. "كم البطارية؟". */
    fun answer(kind: String, s: DeviceSnapshot): String = when (kind) {
        "battery" -> "البطارية ${s.batteryPct}%" + (if (s.charging) " وهي تُشحن الآن" else "") +
            "، وحرارتها ${String.format(Locale.US, "%.0f", s.batteryTempC)}°م."
        "storage" -> "المساحة المتاحة ${gb(s.storageFree)} غيغابايت من ${gb(s.storageTotal)} (المستخدم ${s.storageUsedPct}%)."
        "ram" -> "الذاكرة العشوائية المتاحة ${gb(s.ramAvail)} غيغابايت من ${gb(s.ramTotal)} (المستخدم ${s.ramUsedPct}%)."
        "network" -> if (s.network == "none") "الهاتف غير متصل بالإنترنت الآن."
        else "الاتصال عبر ${networkAr(s)}" + (if (s.online) " ويعمل" else " لكن الإنترنت لا يصل") +
            (if (s.downMbps > 0) "، بسرعة تقديرية ${s.downMbps} ميغابت/ث." else ".")
        else -> "الهاتف ${s.model}، أندرويد ${s.android}، يعمل منذ ${s.uptimeHours} ساعة دون إعادة تشغيل."
    }

    data class Finding(val level: Int, val text: String)   // 0 ok, 1 warn, 2 critical

    fun findings(s: DeviceSnapshot): List<Finding> {
        val out = mutableListOf<Finding>()
        val freePct = 100 - s.storageUsedPct
        when {
            freePct < 8 -> out += Finding(2, "التخزين شبه ممتلئ (المتاح ${gb(s.storageFree)} غيغابايت فقط). هذا يبطّئ الهاتف ويمنع التحديثات.")
            freePct < 18 -> out += Finding(1, "التخزين يقترب من الامتلاء (المتاح ${gb(s.storageFree)} غيغابايت).")
        }
        if (s.batteryTempC >= 42f) out += Finding(2, "حرارة البطارية مرتفعة (${String.format(Locale.US, "%.0f", s.batteryTempC)}°م). أبعد الهاتف عن الشحن والألعاب حتى يبرد.")
        else if (s.batteryTempC >= 38f) out += Finding(1, "حرارة البطارية أعلى من المعتاد (${String.format(Locale.US, "%.0f", s.batteryTempC)}°م).")
        if (!s.batteryHealthOk) out += Finding(1, "النظام يبلّغ أن حالة البطارية ليست «جيدة».")
        if (s.batteryPct in 0..15 && !s.charging) out += Finding(1, "شحن البطارية منخفض (${s.batteryPct}%).")
        if (s.lowMemory || s.ramUsedPct >= 88) out += Finding(1, "الذاكرة العشوائية مضغوطة (المستخدم ${s.ramUsedPct}%). أغلق التطبيقات غير المستخدمة.")
        if (s.network == "none") out += Finding(1, "لا يوجد اتصال بالإنترنت.")
        else if (!s.online) out += Finding(1, "متصل بـ${networkAr(s)} لكن الإنترنت لا يصل.")
        if (s.uptimeHours >= 24 * 7) out += Finding(1, "لم يُعد تشغيل الهاتف منذ ${s.uptimeHours / 24} يوماً. إعادة التشغيل تحل كثيراً من البطء.")
        return out
    }

    /** Full Arabic health report (markdown) produced locally. */
    fun report(s: DeviceSnapshot): String = buildString {
        val f = findings(s)
        val worst = f.maxOfOrNull { it.level } ?: 0
        append(
            when (worst) {
                2 -> "الهاتف يحتاج إلى انتباه: وجدت ${f.size} ملاحظة، منها ما هو مهم."
                1 -> "حالة الهاتف مقبولة، مع ${f.size} ملاحظة تستحق المعالجة."
                else -> "حالة الهاتف جيدة، ولم أجد ما يستدعي القلق."
            }
        )
        append("\n\n## القراءات الحالية\n")
        append("| البند | القراءة |\n|---|---|\n")
        append("| البطارية | **${s.batteryPct}%**").append(if (s.charging) " (تُشحن)" else "").append(" · ${String.format(Locale.US, "%.0f", s.batteryTempC)}°م |\n")
        append("| التخزين | متاح **${gb(s.storageFree)}** من ${gb(s.storageTotal)} غ.ب |\n")
        append("| الذاكرة | متاح **${gb(s.ramAvail)}** من ${gb(s.ramTotal)} غ.ب |\n")
        append("| الشبكة | ${networkAr(s)}").append(if (s.network != "none" && !s.online) " (بلا إنترنت)" else "").append(" |\n")
        append("| التشغيل | منذ ${s.uptimeHours} ساعة |\n")
        if (f.isNotEmpty()) {
            append("\n## الملاحظات\n")
            f.sortedByDescending { it.level }.forEach { append("- ").append(if (it.level == 2) "**مهم:** " else "").append(it.text).append('\n') }
            append("\n## ماذا أفعل؟\n")
            append("- اطلب مني مثلاً: «ابحث عن سبب امتلاء التخزين» أو «لماذا تنفد البطارية بسرعة» وسأفحص الإعدادات وأعطيك تقريراً مفصلاً.\n")
        }
        append("\n").append(s.model).append(" · أندرويد ").append(s.android)
    }
}
