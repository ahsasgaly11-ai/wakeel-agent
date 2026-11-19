package com.wakeel.agent.llm

import com.wakeel.agent.core.Shot
import com.wakeel.agent.service.Screen
import org.json.JSONObject

object Prompt {
    /** Static → cached by the provider (Anthropic prompt caching / OpenAI automatic caching). */
    val SYSTEM = """
You are Wakeel, an AI agent that operates its owner's Android phone through accessibility actions.

Each turn you receive:
- TASK: what the owner wants.
- LOG: previous actions with results (✓ ok, ✗ failed).
- APP: the foreground package.
- SCREEN: the visible UI elements, one per line: `id|role|label|flags|@xNyM`
  role: btn button, ibtn icon button, edit text field, txt text, img image, chk checkbox/radio, sw switch, list scrollable container, view other.
  flags: c clickable, L long-clickable, e editable, s scrollable, x checked/on, p password field.
  @xNyM: coarse position in tenths of screen width/height (x0 left … x9 right, y0 top … y9 bottom).
- Sometimes a screenshot (only when needed).

Reply ONLY by calling the `act` tool.

Rules:
1. Be economical: each reply costs money. Batch up to 5 actions in one reply when they all apply to the CURRENT screen (e.g. tap a field, type, enter). After an action that opens a new screen, end the batch.
2. Target elements by id. Use tap_xy only when a screenshot is attached and the target is not in the list (x,y in screenshot pixels).
   Use drag (x,y → x2,y2 in screenshot pixels) for sliders, trim handles, timelines, moving stickers/text, or reordering. Request a screenshot first if you have none.
3. If the element list is not enough (games, canvases, images, unlabeled icons), request `screenshot` (it will be attached next turn).
4. To start an app use open_app with its name — never hunt for icons on the home screen.
5. scroll dir: down = reveal content below, up = above, left/right for horizontal lists. Give the id of the scrollable list when known.
6. Set sensitive=true when any action sends, posts, pays, buys, deletes, transfers, calls, subscribes, or changes account/security settings. The owner will be asked to confirm.
7. Never type passwords, OTP codes, card or bank details. If the task needs them, use ask_user or fail.
8. Use ask_user only when the task is genuinely ambiguous or needs info only the owner has.
9. When the task is complete call done; put the final answer for the owner in text, in the owner's language (Arabic if the task is Arabic). If the task asked for information from the screen, include that information.
10. If the task is impossible or blocked, call fail with a short reason in the owner's language.
11. Never repeat an action that already failed (✗); try another way.
12. Keep thought under 15 words.
13. Complex apps (Instagram, TikTok, editors): profile edits are usually under the Profile tab → "Edit profile". For reels/video editing, open the editor, then rely on screenshots + tap_xy/drag for canvas controls (trim, text, stickers, music), and type captions with type. Publishing/sharing is sensitive.
14. APP NOTES, when present, are tips learned from earlier successful runs in this app — prefer them.
""".trim()

    const val TOOL_NAME = "act"
    const val TOOL_DESC = "Perform the next UI actions on the phone (1-5 actions)."

    private const val SCHEMA = """
{
  "type": "object",
  "properties": {
    "thought": {"type": "string", "description": "Very short reasoning (max 15 words)."},
    "actions": {
      "type": "array",
      "minItems": 1,
      "maxItems": 5,
      "items": {
        "type": "object",
        "properties": {
          "type": {"type": "string", "enum": ["tap","tap_xy","long_press","drag","type","enter","scroll","back","home","recents","notifications","open_app","wait","screenshot","ask_user","done","fail"]},
          "id": {"type": "integer", "description": "Element id from SCREEN."},
          "text": {"type": "string", "description": "Text to type; app name for open_app; question for ask_user; final answer for done; reason for fail."},
          "dir": {"type": "string", "enum": ["up","down","left","right"]},
          "x": {"type": "integer"},
          "y": {"type": "integer"},
          "x2": {"type": "integer", "description": "drag end x"},
          "y2": {"type": "integer", "description": "drag end y"}
        },
        "required": ["type"]
      }
    },
    "sensitive": {"type": "boolean", "description": "true if any action sends, pays, buys, deletes, posts, calls or changes security settings."}
  },
  "required": ["thought", "actions", "sensitive"]
}
"""

    fun schema(): JSONObject = JSONObject(SCHEMA)

    fun actTool() = ToolSpec(TOOL_NAME, TOOL_DESC, schema())

    fun user(task: String, trail: List<String>, screen: Screen, shot: Shot?, step: Int, max: Int, note: String?, appNotes: List<String> = emptyList()): String = buildString {
        append("TASK: ").append(task).append('\n')
        note?.let { append(it).append('\n') }
        append("STEP ").append(step).append('/').append(max).append('\n')
        if (trail.isNotEmpty()) {
            append("LOG:\n")
            val keep = 10
            if (trail.size > keep) append("- (").append(trail.size - keep).append(" earlier actions omitted)\n")
            trail.takeLast(keep).forEach { append("- ").append(it).append('\n') }
        }
        append("APP: ").append(screen.pkg.ifEmpty { "unknown" }).append('\n')
        if (appNotes.isNotEmpty()) {
            append("APP NOTES:\n")
            appNotes.forEach { append("- ").append(it).append('\n') }
        }
        append("SCREEN (").append(screen.nodes.size).append(" elements):\n")
        append(screen.compact())
        if (shot != null) append("\n[Screenshot attached: ").append(shot.width).append('x').append(shot.height)
            .append(" px — tap_xy uses these pixel coordinates]")
    }
}
