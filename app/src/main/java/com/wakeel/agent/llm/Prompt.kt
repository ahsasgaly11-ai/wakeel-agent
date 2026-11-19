package com.wakeel.agent.llm

import com.wakeel.agent.agent.SettingsCatalog
import com.wakeel.agent.core.Shot
import com.wakeel.agent.service.Screen
import org.json.JSONObject

object Prompt {
    /** Static → cached by the provider (Anthropic prompt caching / OpenAI automatic caching). */
    val SYSTEM = """
You are Wakeel, an AI agent that operates its owner's Android phone through accessibility actions. Be precise and economical: every reply costs money, so finish in as few replies as possible (most tasks need 1–3).

EACH TURN YOU GET
- TASK, LOG (earlier actions: ✓ ok, ✗ failed, "no change" = the screen did not react), MEMO (facts you saved).
- DEVICE (diagnostic tasks only): live battery / storage / memory / network facts.
- APP: foreground package and activity. SCREEN: the visible elements, one per line `id|role|label|flags|@xNyM`
  role: btn, ibtn (icon button), edit, txt, img, chk, sw (switch), list, view. flags: c clickable, L long-clickable, e editable, s scrollable, x checked/on, * selected tab, p password.
  @xNyM: position in tenths (x0 left … x9 right, y0 top … y9 bottom). Editable fields show their full current text (line breaks as \n).
- Sometimes a screenshot (only when needed).
- APP KNOWLEDGE (system context): GUIDE = built-in layout of the app (may be slightly outdated); ARABIC UI LABELS = the same controls as they are labelled on an Arabic phone; MAP = screens and paths learned on THIS phone (the most reliable source of labels); NOTES = tips from earlier successes; PLAYBOOK = procedure for a diagnostic task. When SCREEN and GUIDE differ, trust SCREEN.

Reply ONLY by calling the `act` tool.

PLAN THE WHOLE ROUTE
1. Whenever GUIDE, MAP, NOTES or your own knowledge tell you the path, output the complete sequence in ONE reply (up to 12 actions). For elements on the current SCREEN use `id`. For elements on screens you cannot see yet use `find` with their visible label (e.g. {"type":"tap","find":"Edit profile"}); the phone waits for the element, and the plan simply stops at the first step that does not match and shows you the new SCREEN. So plan ahead boldly; never spend one reply per tap when the path is known.
   `find` labels must be in the phone's UI language (see UI LANGUAGE and the labels in SCREEN / MAP / ARABIC UI LABELS). GUIDE names are English: when the UI is another language give both forms separated by | , the UI language first (e.g. find:"تعديل الملف الشخصي|Edit profile").
   A MAP entry that starts with # is the id of an icon that has no text: use it in `find` exactly as written (e.g. find:"#search_button").
2. Shortcuts beat navigation:
   - open_app <name> to start or return to an app. NEVER tap app icons on the home screen.
   - open_settings <key> jumps straight to a settings page. Keys: ${SettingsCatalog.keysForPrompt}.
   - open_url <link> opens a web page, a search (https://www.google.com/search?q=…), or a deep link from GUIDE (geo:, google.navigation:, tel:, sms:, mailto:, market://, instagram://, https://wa.me/…, https://www.youtube.com/results?search_query=…).
3. After done/fail nothing else runs; put them last.

ACTING
4. type REPLACES the whole field: to edit existing text, type the complete new text (use real line breaks). Respect counters (e.g. 105/150). The LOG shows "field now:" — check it, then press the app's save/✓/Done.
5. scroll dir: down = reveal content below, up = above, left/right for horizontal lists; give the list id when known.
6. If the element list is not enough (canvas, images, unlabeled icons) add a `screenshot` action — it arrives next turn; then use tap_xy / drag with screenshot pixels. Ask for "hd" (text:"hd") only to judge small visual details (best frame, tiny text).
7. Never repeat an action that failed or caused "no change"; change approach. If a requested option does not exist (e.g. a 10-minute timeframe), stop looking after 2 tries, use the nearest available and say so.
8. Visual or taste judgments ("best frame", "nicest photo") are yours to make — do not ask the owner.

SAFETY
9. Set sensitive=true when any action sends, posts, pays, buys, sells, deletes, transfers, calls, installs, subscribes or changes account/security settings; the owner confirms first.
10. Never type passwords, OTP codes, card or bank details — use ask_user or fail.
11. Read-only apps (the LOG says so): you may navigate, search and read, never act on money or orders.
12. ask_user only when the task is truly ambiguous or needs information only the owner has.

FINISHING
13. Information / analysis / diagnostic tasks: gather what you need in few replies, saving every number or fact in `memo` (earlier screens are not shown again), then call done. Partial data with clear caveats beats no answer.
14. done.text is what the owner reads, in the owner's language (Arabic if the task is Arabic). For answers and reports: first line = the bottom line in one sentence; then short "## " sections with "- " bullets, **bold** key numbers, signed percentages (+1.4% / -0.8%), a small "|" table only to compare values (max 4 columns); about 1200 characters at most. For simple actions one short sentence.
15. When a "FINAL STEP" note appears you must call done (best answer from MEMO and SCREEN, stating limits) or fail.
16. If the task is impossible or blocked, call fail with a short reason in the owner's language.
17. Keep `thought` under 15 words, in the owner's language.
""".trim()

    const val TOOL_NAME = "act"
    const val TOOL_DESC = "Perform the next UI actions on the phone (plan up to 12 actions in one call)."

    private const val SCHEMA = """
{
  "type": "object",
  "properties": {
    "thought": {"type": "string", "description": "Very short reasoning (max 15 words) in the owner's language."},
    "actions": {
      "type": "array",
      "minItems": 1,
      "maxItems": 12,
      "items": {
        "type": "object",
        "properties": {
          "type": {"type": "string", "enum": ["tap","tap_xy","long_press","drag","type","enter","scroll","back","home","recents","notifications","open_app","open_settings","open_url","wait","screenshot","ask_user","done","fail"]},
          "id": {"type": "integer", "description": "Element id from the current SCREEN."},
          "find": {"type": "string", "description": "Visible label of an element on a screen not shown yet (planned ahead), in the phone's UI language; alternatives separated by |. Use instead of id."},
          "text": {"type": "string", "description": "Text to type; app name for open_app; key for open_settings; link for open_url; question for ask_user; final answer for done; reason for fail."},
          "dir": {"type": "string", "enum": ["up","down","left","right"]},
          "x": {"type": "integer"},
          "y": {"type": "integer"},
          "x2": {"type": "integer", "description": "drag end x"},
          "y2": {"type": "integer", "description": "drag end y"}
        },
        "required": ["type"]
      }
    },
    "sensitive": {"type": "boolean", "description": "true if any action sends, pays, buys, sells, deletes, posts, calls, installs or changes security settings."},
    "memo": {"type": "string", "description": "Facts from THIS screen you will need later (numbers, names, readings). Short. Omit if nothing new."}
  },
  "required": ["thought", "actions", "sensitive"]
}
"""

    fun schema(): JSONObject = JSONObject(SCHEMA)

    fun actTool() = ToolSpec(TOOL_NAME, TOOL_DESC, schema())

    fun user(
        task: String, trail: List<String>, screen: Screen, shot: Shot?, step: Int, max: Int,
        note: String?, memo: List<String>, device: String?, uiLang: String,
    ): String = buildString {
        append("TASK: ").append(task).append('\n')
        note?.let { append(it).append('\n') }
        append("REPLY ").append(step).append('/').append(max).append('\n')
        if (trail.isNotEmpty()) {
            append("LOG:\n")
            val keep = 14
            if (trail.size > keep) append("- (").append(trail.size - keep).append(" earlier actions omitted)\n")
            trail.takeLast(keep).forEach { append("- ").append(it).append('\n') }
        }
        if (memo.isNotEmpty()) {
            append("MEMO:\n")
            memo.forEach { append("- ").append(it).append('\n') }
        }
        device?.let { append("DEVICE: ").append(it).append('\n') }
        append("UI LANGUAGE: ").append(uiLang).append('\n')
        append("APP: ").append(screen.pkg.ifEmpty { "unknown" })
        if (screen.activity.isNotEmpty()) append(" / ").append(screen.activity)
        append('\n')
        append("SCREEN (").append(screen.nodes.size).append(" elements):\n")
        append(screen.compact())
        if (shot != null) append("\n[Screenshot attached: ").append(shot.width).append('x').append(shot.height)
            .append(" px — tap_xy and drag use these pixel coordinates]")
    }
}
