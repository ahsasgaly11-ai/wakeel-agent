package com.wakeel.agent.agent

import com.wakeel.agent.core.Settings
import com.wakeel.agent.core.Text

object Safety {
    private val sensitive = Regex(
        "(ارسال|ارسل|send|post|نشر|انشر|share|مشاركه|دفع|ادفع|pay|شراء|اشتر|buy|purchase|order|اطلب|checkout|" +
            "حذف|احذف|delete|remove|ازاله|تحويل|حول|transfer|تاكيد|اكد|confirm|subscribe|اشترك|اتصال|call|" +
            "uninstall|الغاء التثبيت|reset|ضبط المصنع|تسجيل الخروج|logout|log out|sign out|حظر|block|ابلاغ|report)",
        RegexOption.IGNORE_CASE,
    )

    private val alwaysAllowed = setOf("com.android.systemui")

    fun isSensitiveLabel(label: String): Boolean = label.isNotBlank() && sensitive.containsMatchIn(Text.norm(label))

    /** Returns a user-facing reason if the agent must not touch [pkg], otherwise null. */
    fun blockReason(pkg: String, s: Settings, apps: AppIndex): String? {
        if (pkg.isBlank() || pkg in alwaysAllowed || pkg in apps.launcherPackages()) return null
        val label = apps.byPackage(pkg)?.label ?: pkg
        if (pkg in s.blockedPkgs) return "التطبيق «$label» محجوب في إعدادات الأمان، لذلك توقّف الوكيل."
        val p = pkg.lowercase()
        val hit = s.blockedKeywords.split(',').map { it.trim().lowercase() }.firstOrNull { it.length >= 3 && p.contains(it) }
        if (hit != null) return "التطبيق «$label» محمي (تطبيقات البنوك والمحافظ وكلمات المرور)، لذلك توقّف الوكيل."
        if (s.allowlistMode && pkg !in s.allowedPkgs) return "التطبيق «$label» ليس ضمن قائمة التطبيقات المسموحة."
        return null
    }
}
