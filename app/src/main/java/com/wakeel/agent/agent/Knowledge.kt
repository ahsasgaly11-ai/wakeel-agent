package com.wakeel.agent.agent

import com.wakeel.agent.core.Text

/**
 * Built-in knowledge the agent ships with: how popular apps are laid out, direct links into them,
 * every phone-settings page it can jump to, and diagnostic playbooks.
 * Pure Kotlin (no Android) so it is unit-tested on CI. Guides are hints — the live screen always wins.
 */
data class AppGuide(val keys: List<String>, val name: String, val category: String, val guide: String) {
    fun matches(pkg: String): Boolean = keys.any { if (it.startsWith("=")) pkg == it.drop(1) else pkg.contains(it) }

    /** The same controls as they are labelled when the phone is in Arabic ("English=عربي; …"). Empty if not listed. */
    val ar: String get() = ArabicLabels.of(name)
}

/**
 * Arabic interface labels for the built-in guides, which are written in English.
 * With these the first plan in an Arabic phone can already name the right buttons (find:"تعديل الملف الشخصي|Edit profile").
 * Wording differs slightly between app versions — the live screen always wins.
 */
object ArabicLabels {
    private val byGuide: Map<String, String> = mapOf(
        "Instagram" to "Home=الصفحة الرئيسية; Search=البحث والاستكشاف; Create=إنشاء|منشور جديد; Reels=Reels|ريلز; Profile=الملف الشخصي; " +
            "Edit profile=تعديل الملف الشخصي; Share profile=مشاركة الملف الشخصي; menu=الخيارات|القائمة; Settings and privacy=الإعدادات والنشاط|الإعدادات والخصوصية; " +
            "Your activity=نشاطك; Archive=الأرشيف; Saved=المحفوظات|العناصر المحفوظة; Name=الاسم; Username=اسم المستخدم; Bio=السيرة الذاتية|النبذة التعريفية; Links=الروابط; Done=تم; " +
            "Your reels=مقاطع ريلز الخاصة بك; More=المزيد|خيارات إضافية; Edit=تعديل; Edit cover=تعديل صورة الغلاف|الغلاف; Delete=حذف; Insights=الرؤى|عرض الرؤى; " +
            "Next=التالي; Share=مشاركة; Caption=اكتب شرحاً توضيحياً|الشرح التوضيحي; Message=مراسلة|رسالة; Follow=متابعة",
        "WhatsApp" to "Chats=الدردشات; Updates=المستجدات|الحالة; Communities=المجتمعات; Calls=المكالمات; Search=بحث; Message field=اكتب رسالة|مراسلة|الرسالة; " +
            "Send=إرسال; Attach=إرفاق; More options=خيارات أكثر|خيارات إضافية; New group=مجموعة جديدة; Linked devices=الأجهزة المرتبطة; Starred=الرسائل المميزة بنجمة; Settings=الإعدادات",
        "Telegram" to "Search=بحث; Message=رسالة|الرسالة; Send=إرسال; Contacts=جهات الاتصال; Saved Messages=الرسائل المحفوظة; Settings=الإعدادات; Attach=إرفاق",
        "Snapchat" to "Map=الخريطة; Chat=الدردشة; Camera=الكاميرا; Stories=القصص; Spotlight=الأضواء; Settings=الإعدادات",
        "TikTok" to "Home=الرئيسية; Friends=الأصدقاء; Inbox=صندوق الوارد; Profile=الملف الشخصي; Edit profile=تعديل الملف الشخصي; Search=بحث; Settings and privacy=الإعدادات والخصوصية; Post=نشر",
        "X (Twitter)" to "Home=الرئيسية; Search=بحث|استكشاف; Notifications=الإشعارات; Messages=الرسائل; Post=نشر; Profile=الملف الشخصي; Edit profile=تعديل الملف الشخصي",
        "Facebook" to "Home=الصفحة الرئيسية; Video=فيديو; Friends=الأصدقاء; Notifications=الإشعارات; Menu=القائمة; What's on your mind?=بم تفكر؟; Post=نشر; Settings & privacy=الإعدادات والخصوصية; Search=بحث",
        "Messenger" to "Chats=الدردشات; Search=بحث; Message=رسالة|مراسلة; Send=إرسال",
        "LinkedIn" to "Home=الرئيسية; My Network=شبكتي; Post=نشر; Notifications=الإشعارات; Jobs=الوظائف; Search=بحث",
        "YouTube" to "Home=الصفحة الرئيسية; Shorts=Shorts; Create=إنشاء; Subscriptions=الاشتراكات; You=أنت; Search=بحث; Like=أعجبني; Share=مشاركة; Save=حفظ; Comments=التعليقات; Settings=الإعدادات",
        "Music" to "Home=الصفحة الرئيسية; Search=بحث; Library=المكتبة; Play=تشغيل; Pause=إيقاف مؤقت; Next=التالي",
        "Streaming" to "Home=الرئيسية; Search=بحث; Play=تشغيل; My List=قائمتي; Downloads=التنزيلات",
        "Browser" to "Address bar=ابحث أو اكتب عنوان URL|البحث أو كتابة عنوان الويب; More options=خيارات إضافية; New tab=علامة تبويب جديدة; History=السجل|المحفوظات; " +
            "Downloads=التنزيلات; Bookmarks=الإشارات المرجعية|الإشارات; Find in page=البحث في الصفحة; Translate=ترجمة; Desktop site=موقع إلكتروني لسطح المكتب; Settings=الإعدادات",
        "Email" to "Search=البحث في البريد; Compose=إنشاء|إنشاء رسالة; To=إلى; Subject=الموضوع; Body=إنشاء رسالة إلكترونية; Send=إرسال; Inbox=البريد الوارد",
        "Google Maps" to "Search here=البحث هنا; Directions=الاتجاهات; Start=بدء; Explore=استكشاف; You=أنت; Contribute=مساهمة",
        "Play Store" to "Search=بحث; Install=تثبيت; Update=تحديث; Open=فتح; Uninstall=إلغاء التثبيت; Manage apps & device=إدارة التطبيقات والجهاز; Updates available=التحديثات المتاحة; Manage=إدارة",
        "Settings" to "Connections=الاتصالات; Connected devices=الأجهزة المتصلة; Modes and Routines=الأوضاع والتطبيقات الروتينية; Sounds and vibration=الأصوات والاهتزاز; Notifications=الإشعارات; " +
            "Display=الشاشة; Battery=البطارية; Wallpaper and style=الخلفية والنمط; Home screen=الشاشة الرئيسية; Lock screen=شاشة القفل; Security and privacy=الأمان والخصوصية; Location=الموقع; " +
            "Accounts and backup=الحسابات والنسخ الاحتياطي; Advanced features=الميزات المتقدمة; Digital Wellbeing=الرفاهية الرقمية; Device care=العناية بالجهاز; Storage=مكان التخزين|التخزين; " +
            "Memory=الذاكرة; Apps=التطبيقات|التطبيقات المثبتة; General management=الإدارة العامة; Accessibility=إمكانية الوصول; Software update=تحديث البرنامج; About phone=حول الهاتف; " +
            "Search=بحث; Wi-Fi=Wi-Fi|الواي فاي; Bluetooth=البلوتوث; Mobile networks=شبكات الهاتف المحمول; Data usage=استخدام البيانات; Airplane mode=وضع الطيران; " +
            "Dark mode=الوضع الداكن|داكن; Brightness=السطوع; Font size=حجم الخط; Screen timeout=زمن توقف الشاشة; Language=اللغة; Reset=إعادة الضبط; Recycle bin=سلة المحذوفات; " +
            "Network & internet (Pixel)=الشبكة والإنترنت; Sort by size=الحجم|فرز حسب الحجم; Clear cache=مسح الذاكرة المؤقتة; Force stop=فرض الإيقاف",
        "Phone" to "Keypad=لوحة المفاتيح; Recents=الأخيرة; Contacts=جهات الاتصال; Call=اتصال",
        "Messages (SMS)" to "Compose=إنشاء رسالة|بدء محادثة; Recipient=المستلم; Message field=رسالة|رسالة نصية; Send=إرسال",
        "Contacts" to "Search=بحث; Create=إنشاء جهة اتصال; Name=الاسم; Phone=الهاتف; Save=حفظ; Edit=تعديل; Delete=حذف",
        "Camera" to "Photo=صورة; Video=فيديو; Portrait=بورتريه|عمودي; More=المزيد; Switch camera=تبديل الكاميرا",
        "Gallery / Photos" to "Pictures=الصور; Albums=الألبومات; Stories=القصص; Search=بحث; All=الكل; Select=تحديد; Share=مشاركة; Delete=حذف; More=المزيد; " +
            "Move to album=نقل إلى ألبوم|نقل إلى الألبوم; Copy to album=نسخ إلى ألبوم|نسخ إلى الألبوم; Create album=إنشاء ألبوم|إنشاء الألبوم; Album=ألبوم; Create=إنشاء; " +
            "Camera=الكاميرا; Edit=تعديل; Details=التفاصيل; Done=تم",
        "Files" to "Images=الصور; Videos=الفيديوهات|ملفات الفيديو; Audio=الملفات الصوتية; Documents=المستندات; Downloads=التنزيلات; Installation files=ملفات التثبيت; " +
            "Internal storage=مكان التخزين الداخلي; Analyse storage=تحليل مساحة التخزين; Recycle bin=سلة المحذوفات; All=الكل; Move=نقل; Copy=نسخ; Move here=نقل إلى هنا; Copy here=نسخ إلى هنا; Create folder=إنشاء مجلد; Search=بحث",
        "Clock" to "Alarm=المنبه; World clock=الساعة العالمية; Stopwatch=ساعة الإيقاف; Timer=المؤقت",
        "Calendar" to "Title=العنوان; Save=حفظ; Today=اليوم",
        "Notes" to "Search=بحث; Title=العنوان; New note=ملاحظة جديدة",
        "Crypto exchange" to "Home=الرئيسية; Markets=الأسواق; Trade=تداول; Spot=فوري|التداول الفوري; Futures=العقود الآجلة; Assets=الأصول; Wallet=المحفظة; Search=بحث; " +
            "Chart=الرسم البياني|الشارت; Order book=دفتر الطلبات; Trades=الصفقات; Info=المعلومات; AI analysis=تحليل AI; Indicators=المؤشرات; " +
            "5m=5دقيقة; 15m=15دقيقة; 30m=30دقيقة; 1h=1ساعة; 4h=4ساعة; 1D=1يوم; More=المزيد; Buy=شراء; Sell=بيع; Neutral=محايد",
        "Trading / charts" to "Markets=الأسواق; Chart=الرسم البياني; Indicators=المؤشرات; Search=بحث; Buy=شراء; Sell=بيع; Neutral=محايد",
        "Food delivery" to "Search=بحث; Cart=السلة; Checkout=إتمام الطلب|الدفع; Orders=الطلبات; Add=إضافة",
        "Shopping" to "Search=بحث; Add to cart=أضف إلى السلة|إضافة إلى عربة التسوق; Cart=السلة|عربة التسوق; Orders=الطلبات; Account=الحساب",
        "Ride hailing" to "Where to?=إلى أين؟; Confirm=تأكيد",
        "AI assistant" to "Message=رسالة|اكتب رسالة; Send=إرسال; New chat=محادثة جديدة",
        "Translate" to "Enter text=إدخال نص|أدخل النص; Copy=نسخ",
    )

    fun of(guideName: String): String = byGuide[guideName].orEmpty()
}

data class Playbook(val id: String, val title: String, val pattern: Regex, val text: String)

object KnowledgeBase {
    const val SOCIAL = "تواصل اجتماعي"
    const val CHAT = "مراسلة"
    const val VIDEO = "فيديو وترفيه"
    const val TRADE = "تداول ومال"
    const val WEB = "تصفح"
    const val SYSTEM = "النظام"
    const val TOOLS = "أدوات"
    const val SHOP = "تسوق وتوصيل"
    const val MAPS = "خرائط وتنقل"
    const val WORK = "إنتاجية"
    const val AI = "ذكاء اصطناعي"

    private const val TRADING_GUIDE =
        "Bottom tabs: Home, Markets, Trade (Spot), Futures, Assets/Wallet. Markets → search a symbol (e.g. BTCUSDT) → pair page: last price, 24h change/high/low/volume. " +
            "Chart timeframe tabs that exist: 1m, 5m, 15m, 30m, 1h, 4h, 1D, 1W (others under 'More'); there is NO 10m — use the nearest (5m and 15m) and say so. " +
            "Pair-page tabs: Chart, Order book, Trades, Info/Data, and often 'AI analysis'/'Indicators' with a buy/neutral/sell gauge and counts. " +
            "Analysis recipe (≈4 replies): memo price + 24h stats → open the requested timeframe → memo the indicator summary (gauge, MA/RSI/MACD) → done with a structured analysis that ends with 'not financial advice'. " +
            "NEVER place, modify or cancel orders, convert, transfer, deposit or withdraw unless the task explicitly orders it (always sensitive). This app is usually read-only for you."

    val guides: List<AppGuide> = listOf(
        AppGuide(
            listOf("com.instagram.android", "instagram.lite"), "Instagram", SOCIAL,
            "Bottom tabs: Home, Search, Create (+), Reels, Profile (avatar, last). Profile: 'Edit profile', 'Share profile', ≡ menu (Settings and privacy, Your activity, Archive, Saved). " +
                "Edit profile: Name, Username, Pronouns, Bio, Links → save with ✓/Done (Bio opens its own page; max 150 chars). " +
                "Own reels: Profile → Reels grid tab → open reel → ⋯ More → Edit (caption; 'Edit cover' → frame strip, drag selector, Done) / Delete / Insights. " +
                "New post or reel: + → pick media → Next → edit (audio, text, stickers: canvas, needs screenshots) → Next → caption → Share (sensitive). " +
                "DMs: Home → messenger icon (top). Deep link: open_url instagram://user?username=NAME opens a profile."
        ),
        AppGuide(
            listOf("=com.whatsapp", "com.whatsapp.w4b"), "WhatsApp", CHAT,
            "Tabs: Chats, Updates, Communities, Calls. Open a chat: search (top) → type the name → tap the result. In chat: 'Message' field, send button (sensitive), attach, camera, mic. " +
                "⋮ menu: New group, Linked devices, Starred, Settings. Fast for a phone number: open_url https://wa.me/<number with country code>?text=<text> then press send."
        ),
        AppGuide(
            listOf("org.telegram", "telegram.messenger", "org.thunderdog"), "Telegram", CHAT,
            "Chat list with search icon (top). ≡ menu: Contacts, Saved Messages, Settings. In chat: 'Message' field, send (sensitive), attach. Deep link: open_url tg://resolve?domain=USERNAME."
        ),
        AppGuide(
            listOf("com.snapchat.android"), "Snapchat", SOCIAL,
            "Opens on Camera. Bottom tabs: Map, Chat, Camera, Stories, Spotlight. Profile: avatar (top-left) → ⚙ settings (top-right). Many controls are unlabeled icons — use a screenshot."
        ),
        AppGuide(
            listOf("musically", "ss.android.ugc.trill", "com.zhiliaoapp"), "TikTok", SOCIAL,
            "Bottom tabs: Home, Friends/Shop, + (create), Inbox, Profile. Search: magnifier (top-right of Home). Profile: 'Edit profile'; ≡ → Settings and privacy. Posting is sensitive."
        ),
        AppGuide(
            listOf("com.twitter.android"), "X (Twitter)", SOCIAL,
            "Bottom tabs: Home, Search, Communities/Grok, Notifications, Messages. Compose: floating + → text → Post (sensitive). Profile: avatar (top-left) → Profile → Edit profile. Deep link: open_url twitter://user?screen_name=NAME."
        ),
        AppGuide(
            listOf("com.facebook.katana", "com.facebook.lite"), "Facebook", SOCIAL,
            "Tabs: Home, Video, Friends, Marketplace, Notifications, Menu. Post: 'What's on your mind?' → Post (sensitive). Menu → Settings & privacy. Search: magnifier (top)."
        ),
        AppGuide(listOf("com.facebook.orca"), "Messenger", CHAT, "Chats list, search (top). In chat: message field, send (sensitive)."),
        AppGuide(
            listOf("com.linkedin.android"), "LinkedIn", SOCIAL,
            "Bottom tabs: Home, My Network, Post, Notifications, Jobs. Profile: avatar (top-left) → View profile → ✎ edit. Search bar on top."
        ),
        AppGuide(
            listOf("=com.google.android.youtube"), "YouTube", VIDEO,
            "Bottom tabs: Home, Shorts, + (create), Subscriptions, You. Search: magnifier (top) → type → enter. " +
                "Fastest search: open_url https://www.youtube.com/results?search_query=<query> (opens results in the app). Video page: like, share, save, comments; ⚙ for quality and speed."
        ),
        AppGuide(listOf("youtube.music", "com.spotify.music", "anghami"), "Music", VIDEO, "Bottom tabs: Home, Search, Library. Search → type → tap the result → play. Player bar at the bottom: play/pause, next."),
        AppGuide(listOf("com.netflix", "shahid", "com.disney", "com.amazon.avod"), "Streaming", VIDEO, "Home rows of titles; Search (magnifier or tab) → type → tap title → Play. My List/Downloads tabs."),
        AppGuide(
            listOf("=com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.brave.browser", "com.microsoft.emmx"), "Browser", WEB,
            "Address bar ('Search or type URL'): tap → type → enter. ⋮ menu: New tab, History, Downloads, Bookmarks, Find in page, Translate, Desktop site, Settings. Tab switcher: square with a number. " +
                "Fastest: open_url <https url>, or open_url https://www.google.com/search?q=<query>. Read a page from SCREEN text; scroll down at most 3 times, memo facts as you go."
        ),
        AppGuide(
            listOf("=com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email"), "Email", WORK,
            "Inbox list; search bar on top; Compose (pencil, bottom) → To, Subject, body → Send (sensitive). ≡: folders/labels. Fast compose: open_url mailto:<address>?subject=<s>&body=<b>."
        ),
        AppGuide(
            listOf("com.google.android.apps.maps"), "Google Maps", MAPS,
            "Search bar on top ('Search here'). After a search: Directions → Start. Tabs: Explore, You, Contribute. Fast: open_url geo:0,0?q=<place>; turn-by-turn: open_url google.navigation:q=<place>."
        ),
        AppGuide(
            listOf("=com.android.vending"), "Play Store", TOOLS,
            "Search tab/bar. App page: Install / Update / Open / Uninstall (install and uninstall are sensitive). Avatar (top) → Manage apps & device → Updates available, or Manage → sort by size to find big apps. " +
                "Fast: open_url market://details?id=<package> or market://search?q=<query>."
        ),
        AppGuide(
            listOf("=com.android.settings", "com.samsung.android.lool"), "Settings", SYSTEM,
            "Samsung One UI main list: Connections (Wi-Fi, Bluetooth, SIM manager, Mobile networks, Data usage, Hotspot, Airplane mode), Connected devices, Modes and Routines, Sounds and vibration, Notifications, " +
                "Display (Dark mode, Brightness, Font size, Screen timeout), Battery, Wallpaper and style, Home screen, Lock screen, Security and privacy, Location, Accounts and backup, Google, Advanced features, " +
                "Digital Wellbeing, Device care (Storage, Memory, Battery, Diagnostics), Apps, General management (Language, Keyboard, Date and time, Reset), Accessibility, Software update, About phone. " +
                "The search icon on top finds any setting by name. Prefer open_settings <key> to land on the page directly. Switches: flag x = on. Storage page lists categories (Apps, Images, Videos, Audio, Documents, System, Recycle bin)."
        ),
        AppGuide(listOf("dialer", "com.android.phone", "incallui"), "Phone", SYSTEM, "Tabs: Keypad, Recents, Contacts. Calling is sensitive. To prepare a call: open_url tel:<number> (opens the dialer without calling)."),
        AppGuide(
            listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"), "Messages (SMS)", CHAT,
            "Conversation list; compose button; Recipient field, message field, send (sensitive). Fast: open_url sms:<number>?body=<text>."
        ),
        AppGuide(listOf("contacts"), "Contacts", SYSTEM, "List with search; + to create (Name, Phone → Save). Contact page: call, message, edit, ⋮ delete (sensitive)."),
        AppGuide(listOf("camera"), "Camera", TOOLS, "Shutter (bottom center), switch camera, mode row (Photo, Video, Portrait, More), gallery thumbnail. Icons are often unlabeled — use a screenshot."),
        AppGuide(
            listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"), "Gallery / Photos", TOOLS,
            "Samsung Gallery: bottom tabs Pictures, Albums, Stories (+ ≡ menu). The magnifier searches by place, date, person or thing (e.g. 'India', 'November 2025'): tap the matching suggestion or 'see all' to list every match. " +
                "Selection mode: long_press one thumbnail (or ⋮ → Select / Edit). Then 'All' (top corner) selects everything listed; the circle on a date header selects that whole day. " +
                "Bottom bar: Share, Delete (sensitive), More → 'Move to album' / 'Copy to album' → pick an album or 'Create album' (+) → type its name → Create. " +
                "New empty album: Albums tab → + (or ⋮ → Create album) → name → Create. " +
                "Thumbnails carry at most a type and a date, never what the picture shows: to gather the photos of a trip or a subject, search first; if search finds nothing, ask the owner for the dates. " +
                "Open an item: Edit (pencil), Details (ⓘ: date, place, file name). Google Photos: tabs Photos, Collections/Library, Search; long_press selects; + / 'Add to' → Album → New album."
        ),
        AppGuide(
            listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files", "documentsui"), "Files", TOOLS,
            "Categories: Images, Videos, Audio, Documents, Downloads, Installation files (APK), Compressed. Storage: Internal storage. Samsung My Files: 'Analyse storage' (large files, duplicates, unused apps) and Recycle bin. " +
                "Selection: long_press one item, then 'All'; bottom bar Move / Copy → open the destination → 'Move here' / 'Copy here'; ⋮ → Create folder. Search on top filters by name or type. Deleting is sensitive."
        ),
        AppGuide(listOf("deskclock", "clockpackage"), "Clock", TOOLS, "Tabs: Alarm, World clock, Stopwatch, Timer. + adds an alarm; switches enable/disable."),
        AppGuide(listOf("calendar"), "Calendar", WORK, "Month/week view; + creates an event (Title, time, Save). Tap a day to list its events."),
        AppGuide(listOf("com.samsung.android.app.notes", "com.google.android.keep"), "Notes", WORK, "Notes list; + creates a note (title, body; auto-saves on back). Search on top."),
        AppGuide(listOf("com.binance", "com.mexcpro", "mexc", "com.bybit", "com.okinc", "okex", "kucoin", "bitget", "gateio", "huobi", "com.kraken", "coinbase", "crypto.exchange"), "Crypto exchange", TRADE, TRADING_GUIDE),
        AppGuide(listOf("tradingview", "metatrader", "exness", "etoro", "investing"), "Trading / charts", TRADE, TRADING_GUIDE),
        AppGuide(
            listOf("talabat", "snoonu", "com.ubercab.eats", "hungerstation", "rafeeq", "keeta"), "Food delivery", SHOP,
            "Home with search and restaurant list → restaurant → add items → cart → checkout (placing an order is sensitive). Orders tab shows status."
        ),
        AppGuide(listOf("com.amazon.mShop", "noon", "aliexpress", "shein", "temu"), "Shopping", SHOP, "Search bar on top → results → product page (price, reviews, Add to cart). Cart → checkout (sensitive). Account/Orders tab."),
        AppGuide(listOf("com.ubercab", "careem", "karwa", "yandex.taxi", "ru.yandex.taxi"), "Ride hailing", MAPS, "'Where to?' → destination → choose ride → Confirm (sensitive: it books a paid ride)."),
        AppGuide(listOf("com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard", "gemini"), "AI assistant", AI, "Chat screen: message field at the bottom → send. ≡ or top-left: history/new chat. Wait for the reply to finish before reading it."),
        AppGuide(listOf("us.zoom", "com.microsoft.teams", "com.google.android.apps.meetings"), "Meetings", WORK, "Tabs: Meetings/Chat/Calendar. Join with an ID or link; in-call bar: mute, video, share, leave."),
        AppGuide(listOf("com.google.android.apps.docs", "com.microsoft.office", "com.dropbox"), "Documents / cloud", WORK, "File list with search; + creates or uploads; ⋮ on a file: Share, Rename, Download, Remove (sensitive)."),
        AppGuide(listOf("translate"), "Translate", TOOLS, "Source and target language chips on top; text box 'Enter text'; result below with copy/speak."),
        AppGuide(listOf("calculator"), "Calculator", TOOLS, "Digit and operator buttons; '=' gives the result shown in the display field."),
    )

    fun guideFor(pkg: String): AppGuide? = if (pkg.isBlank()) null else guides.firstOrNull { it.matches(pkg) }

    // ---------------------------------------------------------------- diagnostics

    val playbooks: List<Playbook> = listOf(
        Playbook(
            "storage", "امتلاء التخزين", Regex("(تخزين|مساح|ممتل|امتلا|storage|space)"),
            "Goal: explain what fills the storage and how to free it. 1) open_settings storage → memo used/free and every category size (Apps, Images, Videos, Audio, Documents, System, Recycle bin/Trash, Other). " +
                "2) If Apps is large: open_settings apps → sort by size (⋮ or the sort/filter control → Size) → memo the top 5. 3) Optional: Samsung My Files → 'Analyse storage' for large files and duplicates. " +
                "Report: bottom line, a table of categories, top space users, then 3–5 concrete safe steps (empty Recycle bin, clear caches of the biggest apps, remove unused apps, move videos/photos to cloud or SD, delete downloads). " +
                "Deleting or clearing anything is sensitive — only when the task explicitly asks."
        ),
        Playbook(
            "battery", "استنزاف البطارية", Regex("(بطاري|الشحن|شحن|battery)"),
            "DEVICE already gives level, temperature and health. 1) open_settings battery → memo usage by app since last charge (top consumers) and screen-on time. 2) Check power saving and background usage limits (sleeping apps). " +
                "Report causes and steps: restrict the top background apps, lower brightness/timeout, dark mode, turn off Always On Display / 5G / location for apps that do not need it, battery protection, check Device care → Diagnostics → Battery status. Above 40°C: mention heat."
        ),
        Playbook(
            "slow", "بطء الهاتف", Regex("(بطي|يعلق|تعليق|يهنق|تهنيق|ثقيل|يتجمد|lag|slow)"),
            "Check DEVICE: free storage under 10% and high RAM use are the usual causes; uptime over 7 days → suggest a restart. 1) open_settings storage (memo free space). 2) Device care → Memory → memo top RAM users (Clean now is safe). " +
                "3) open_settings update → check for a software update. Report causes ranked by likelihood with concrete steps."
        ),
        Playbook(
            "heat", "ارتفاع الحرارة", Regex("(حرار|يسخن|سخون|ساخن|heat|hot)"),
            "DEVICE gives battery temperature. open_settings battery → memo top consumers (heavy apps heat the phone). Advise: stop charging while gaming, remove the case, close heavy apps, avoid sun, update software; persistent heat at idle → service check."
        ),
        Playbook(
            "network", "مشاكل الإنترنت", Regex("(انترنت|النت|واي ?فاي|wifi|wi-fi|الشبكه|شبكه|بيانات الجوال|الداتا)"),
            "DEVICE gives the network type and whether internet is validated. Steps by cause: Wi-Fi connected but no internet → open_settings wifi → forget and reconnect, or restart the router; mobile data → open_settings mobile_network (data on, right SIM, roaming, APN) and open_settings data_usage (data saver / limit); " +
                "everything slow → toggle airplane mode, turn off VPN/Private DNS, then General management → Reset → Reset mobile network settings (sensitive). Report what you found and the fix order."
        ),
        Playbook(
            "bluetooth", "مشاكل البلوتوث", Regex("(بلوتوث|bluetooth|ايربود|airpod|buds)"),
            "open_settings bluetooth → is it on, is the device listed as paired/connected? Fix order: toggle Bluetooth, unpair and pair again (device in pairing mode), check the device battery, restart the phone, reset network settings (sensitive)."
        ),
        Playbook(
            "notifications", "الإشعارات لا تصل", Regex("(اشعار|تنبيه|notification)"),
            "For the affected app: open_settings app_info:<app> → Notifications allowed and categories on; Battery → Unrestricted; Mobile data → background data allowed. Also open_settings dnd (Do not disturb off) and battery saver off. Report which switch was the cause."
        ),
        Playbook(
            "sound", "مشاكل الصوت", Regex("(الصوت|صوت|سماعه|مكبر|ميكروفون|المايك|sound|volume)"),
            "open_settings sound → memo volume levels (media, ring, notifications) and sound mode (Sound/Vibrate/Mute); open_settings dnd; open_settings bluetooth (audio may be routed to a connected device). Report the cause."
        ),
        Playbook(
            "appcrash", "تطبيق لا يعمل", Regex("(يتوقف|توقف عن|لا يفتح|ما يفتح|لا يعمل|ما يشتغل|يخرج فجا|يقفل|كراش|crash|يعلق التطبيق)"),
            "open_settings app_info:<app> → Force stop → Storage → Clear cache (safe). Clear data erases the app's data — ask the owner first. Then check for an update: open_url market://details?id=<package>. Still failing: restart the phone, then reinstall (sensitive)."
        ),
        Playbook(
            "general", "تشخيص عام", Regex("(مشكل|عطل|خلل|اصلح|اصلاح|حل |لماذا|ليش|سبب|problem|fix|why)"),
            "Use DEVICE facts first. Jump to the relevant page with open_settings instead of browsing menus. Collect evidence in memo (≤ 6 replies), then report: bottom line, evidence, ranked causes, concrete steps. Anything destructive is sensitive."
        ),
    )

    /** A playbook only when the task reads like a problem or a question, not a plain "open X". */
    fun playbookFor(task: String): Playbook? {
        val t = " " + Text.norm(task) + " "
        val problem = Regex("(مشكل|عطل|خلل|سبب|لماذا|ليش|ما يشتغل|لا يعمل|لا يفتح|ما يفتح|يتوقف|بطي|يعلق|يهنق|ثقيل|تنفد|تخلص|يخلص|ضعيف|مقطوع|ممتل|امتلا|يسخن|حرار|سخون|لا تصل|ما توصل|ما تجي|اصلح|اصلاح|حلل|افحص|فحص|تشخيص|نظف|فرغ|وفر مساح|problem|fix|why|slow|diagnos)")
        if (!problem.containsMatchIn(t)) return null
        return playbooks.firstOrNull { it.id != "general" && it.pattern.containsMatchIn(t) } ?: playbooks.last()
    }
}

/** Every settings page the agent can open directly, with the words people use for it. */
object SettingsCatalog {
    class Page(val key: String, val actions: List<String>, val ar: String, val words: List<String>)

    val pages: List<Page> = listOf(
        Page("wifi", listOf("android.settings.WIFI_SETTINGS"), "الواي فاي", listOf("الواي فاي", "واي فاي", "الوايفاي", "وايفاي", "wifi", "wi-fi", "الشبكات اللاسلكية")),
        Page("bluetooth", listOf("android.settings.BLUETOOTH_SETTINGS"), "البلوتوث", listOf("البلوتوث", "بلوتوث", "bluetooth")),
        Page("connections", listOf("android.settings.WIRELESS_SETTINGS"), "الاتصالات", listOf("الاتصالات", "الشبكة والإنترنت", "الشبكات", "الإنترنت", "connections", "network")),
        Page("airplane", listOf("android.settings.AIRPLANE_MODE_SETTINGS"), "وضع الطيران", listOf("وضع الطيران", "الطيران", "airplane mode")),
        Page("mobile_network", listOf("android.settings.DATA_ROAMING_SETTINGS", "android.settings.NETWORK_OPERATOR_SETTINGS"), "شبكة الجوال", listOf("بيانات الجوال", "البيانات", "شبكة الجوال", "شبكات الجوال", "الشبكة الخلوية", "الداتا", "mobile data", "mobile network")),
        Page("data_usage", listOf("android.settings.DATA_USAGE_SETTINGS"), "استخدام البيانات", listOf("استخدام البيانات", "استهلاك البيانات", "data usage")),
        Page("hotspot", listOf("android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS"), "نقطة الاتصال", listOf("نقطة الاتصال", "نقطة الاتصال المحمولة", "الهوت سبوت", "هوت سبوت", "hotspot")),
        Page("nfc", listOf("android.settings.NFC_SETTINGS"), "NFC", listOf("nfc", "ان اف سي")),
        Page("vpn", listOf("android.settings.VPN_SETTINGS"), "VPN", listOf("vpn", "في بي ان")),
        Page("display", listOf("android.settings.DISPLAY_SETTINGS"), "الشاشة", listOf("الشاشة", "العرض", "السطوع", "الوضع الداكن", "الوضع الليلي", "الوضع المظلم", "حجم الخط", "الخط", "مهلة الشاشة", "display", "brightness", "dark mode")),
        Page("sound", listOf("android.settings.SOUND_SETTINGS"), "الصوت", listOf("الصوت", "الأصوات", "الأصوات والاهتزاز", "النغمات", "نغمة الرنين", "sound", "sounds")),
        Page("notifications", listOf("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "android.settings.NOTIFICATION_SETTINGS"), "الإشعارات", listOf("الإشعارات", "التنبيهات", "notifications")),
        Page("dnd", listOf("android.settings.ZEN_MODE_SETTINGS"), "عدم الإزعاج", listOf("عدم الإزعاج", "وضع عدم الإزعاج", "do not disturb", "dnd")),
        Page("battery", listOf("android.intent.action.POWER_USAGE_SUMMARY", "android.settings.BATTERY_SAVER_SETTINGS"), "البطارية", listOf("البطارية", "الطاقة", "battery")),
        Page("battery_saver", listOf("android.settings.BATTERY_SAVER_SETTINGS"), "توفير الطاقة", listOf("توفير الطاقة", "وضع توفير الطاقة", "battery saver", "power saving")),
        Page("storage", listOf("android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.MEMORY_CARD_SETTINGS"), "التخزين", listOf("التخزين", "المساحة", "مساحة التخزين", "الذاكرة", "storage")),
        Page("apps", listOf("android.settings.MANAGE_APPLICATIONS_SETTINGS", "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS", "android.settings.APPLICATION_SETTINGS"), "التطبيقات", listOf("التطبيقات", "مدير التطبيقات", "البرامج", "apps", "applications")),
        Page("default_apps", listOf("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"), "التطبيقات الافتراضية", listOf("التطبيقات الافتراضية", "default apps")),
        Page("location", listOf("android.settings.LOCATION_SOURCE_SETTINGS"), "الموقع", listOf("الموقع", "المواقع", "تحديد الموقع", "gps", "location")),
        Page("security", listOf("android.settings.SECURITY_SETTINGS"), "الأمان", listOf("الأمان", "الحماية", "قفل الشاشة", "البصمة", "الأمان والخصوصية", "security")),
        Page("privacy", listOf("android.settings.PRIVACY_SETTINGS"), "الخصوصية", listOf("الخصوصية", "privacy")),
        Page("accounts", listOf("android.settings.SYNC_SETTINGS"), "الحسابات", listOf("الحسابات", "الحسابات والنسخ الاحتياطي", "accounts")),
        Page("date", listOf("android.settings.DATE_SETTINGS"), "التاريخ والوقت", listOf("التاريخ", "الوقت", "التاريخ والوقت", "date", "time")),
        Page("language", listOf("android.settings.LOCALE_SETTINGS"), "اللغة", listOf("اللغة", "اللغات", "language")),
        Page("keyboard", listOf("android.settings.INPUT_METHOD_SETTINGS"), "لوحة المفاتيح", listOf("لوحة المفاتيح", "الكيبورد", "keyboard")),
        Page("accessibility", listOf("android.settings.ACCESSIBILITY_SETTINGS"), "إمكانية الوصول", listOf("إمكانية الوصول", "سهولة الاستخدام", "تسهيلات الاستخدام", "accessibility")),
        Page("developer", listOf("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"), "خيارات المطور", listOf("خيارات المطور", "وضع المطور", "خيارات المطورين", "developer options")),
        Page("about", listOf("android.settings.DEVICE_INFO_SETTINGS"), "حول الهاتف", listOf("حول الهاتف", "حول الجهاز", "عن الهاتف", "معلومات البرنامج", "about phone")),
        Page("update", listOf("android.settings.SYSTEM_UPDATE_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS"), "تحديث النظام", listOf("تحديث النظام", "تحديث البرنامج", "التحديثات", "تحديث الهاتف", "software update")),
        Page("main", listOf("android.settings.SETTINGS"), "الإعدادات", listOf("الإعدادات", "الضبط", "settings")),
    )

    private val byKey: Map<String, Page> = pages.associateBy { it.key }
    private val byWord: Map<String, String> = buildMap { pages.forEach { p -> p.words.forEach { put(Text.norm(it), p.key) } } }

    /** Pages whose plain name also means something else ("الإشعارات" = the shade, "الصوت" = volume). */
    val ambiguous = setOf("notifications", "sound", "main", "date", "apps")

    fun page(key: String): Page? = byKey[key.trim().lowercase()]

    fun keyForWords(normalized: String): String? =
        byWord[normalized] ?: (if (!normalized.startsWith("ال")) byWord["ال$normalized"] else null)

    val keysForPrompt: String get() = pages.joinToString(", ") { it.key } + ", app_info:<app name>"
}
