package com.wakeel.agent.agent

import com.wakeel.agent.core.Policy
import com.wakeel.agent.core.Settings
import com.wakeel.agent.core.Text

object Safety {
    /** Actions that need the owner's OK in any app. */
    private val sensitive = Regex(
        "(ارسال|ارسل|send|post|نشر|انشر|share|مشاركه|دفع|ادفع|pay|شراء|اشتر|buy|purchase|order|اطلب|checkout|" +
            "حذف|احذف|delete|remove|ازاله|تحويل|حول|transfer|تاكيد|اكد|confirm|subscribe|اشترك|اتصال|call|" +
            "uninstall|الغاء التثبيت|reset|ضبط المصنع|تسجيل الخروج|logout|log out|sign out|حظر|block|ابلاغ|report|" +
            "مسح البيانات|clear data|فرمته|format|install|تثبيت)",
        RegexOption.IGNORE_CASE,
    )

    /** Anything that moves money or changes a position — refused outright in read-only apps. */
    private val trade = Regex(
        "(buy|sell|long|short|order|confirm|submit|transfer|withdraw|deposit|send|pay|convert|swap|stake|redeem|borrow|repay|" +
            "close position|close all|cancel|place|leverage|margin|subscribe|" +
            "شراء|اشتر|بيع|بع |طلب|تاكيد|اكد|تحويل|سحب|ايداع|ارسال|دفع|تبديل|صفقه|مركز|اغلاق|الغاء|رافعه|هامش|اكتتاب|اقتراض|تسديد)",
        RegexOption.IGNORE_CASE,
    )

    private val searchLike = Regex("(search|بحث|ابحث|find|filter|symbol|coin|pair)", RegexOption.IGNORE_CASE)

    private val alwaysAllowed = setOf("com.android.systemui")

    fun isSensitiveLabel(label: String): Boolean = label.isNotBlank() && sensitive.containsMatchIn(Text.norm(label))

    /** A button that sends what is written in the input field (a message, a post, a reply). */
    private val sends = Regex("(ارسال|ارسل|send|نشر|انشر|post|publish|reply|tweet)", RegexOption.IGNORE_CASE)

    fun isSendLabel(label: String): Boolean = label.isNotBlank() && sends.containsMatchIn(Text.norm(label))

    fun isTradeLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val l = Text.norm(label).replace("order book", "").replace("orderbook", "").replace("دفتر الطلبات", "").replace("سجل الطلبات", "")
        return trade.containsMatchIn("$l ")
    }

    fun isSearchField(label: String, viewId: String): Boolean = searchLike.containsMatchIn(Text.norm(label)) || searchLike.containsMatchIn(viewId)

    private fun keywordHit(pkg: String, keywords: String): Boolean {
        val p = pkg.lowercase()
        return keywords.split(',').map { it.trim().lowercase() }.any { it.length >= 3 && p.contains(it) }
    }

    fun policy(pkg: String, s: Settings, apps: AppIndex): Policy {
        if (pkg.isBlank() || pkg in alwaysAllowed || pkg in apps.launcherPackages()) return Policy.ALLOWED
        s.explicitPolicy(pkg)?.let { return it }
        if (keywordHit(pkg, s.blockedKeywords)) return Policy.BLOCKED
        if (keywordHit(pkg, s.readOnlyKeywords)) return Policy.READ_ONLY
        if (s.allowlistMode) return Policy.BLOCKED
        return Policy.ALLOWED
    }

    /** Returns a user-facing reason if the agent must not touch [pkg], otherwise null. */
    fun blockReason(pkg: String, s: Settings, apps: AppIndex): String? {
        if (policy(pkg, s, apps) != Policy.BLOCKED) return null
        val label = apps.byPackage(pkg)?.label ?: pkg
        return when {
            pkg in s.blockedPkgs -> "التطبيق «$label» محجوب في إعدادات الأمان، لذلك توقّف الوكيل."
            s.allowlistMode && !keywordHit(pkg, s.blockedKeywords) -> "التطبيق «$label» ليس ضمن التطبيقات المسموحة (وضع القائمة البيضاء مفعّل)."
            else -> "التطبيق «$label» محمي (تطبيقات البنوك والمحافظ وكلمات المرور)، لذلك توقّف الوكيل."
        }
    }
}
