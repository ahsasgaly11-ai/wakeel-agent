package com.wakeel.agent.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.EngineState

/** Quick command window opened from the floating bubble. A real activity, so the keyboard and voice input work everywhere. */
class CommandActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ASK = "ask"
        private const val EXTRA_TEACH = "teach"

        /** [teach] opens the window ready to record a task the owner will perform by hand. */
        fun open(ctx: Context, askQuestion: String? = null, teach: Boolean = false) {
            val i = Intent(ctx, CommandActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            if (askQuestion != null) i.putExtra(EXTRA_ASK, askQuestion)
            if (teach) i.putExtra(EXTRA_TEACH, true)
            runCatching { ctx.startActivity(i) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ask = intent.getStringExtra(EXTRA_ASK)
        val teachFirst = intent.getBooleanExtra(EXTRA_TEACH, false)
        setContent {
            WakeelTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    CommandSheet(
                        askQuestion = ask,
                        teachFirst = teachFirst && ask == null,
                        onDismiss = { finish() },
                        onSubmit = { text ->
                            if (ask != null) {
                                Hub.engine.answerAsk(text)
                            } else {
                                // Let this window close so the agent starts on the app you were using
                                Hub.engine.start(text, delayMs = 700)
                            }
                            finish()
                        },
                        onTeach = { phrase ->
                            val msg = when {
                                Hub.engine.isRunning -> "هناك مهمة قيد التنفيذ. أوقفها أولاً."
                                Hub.recorder.start(phrase) ->
                                    if (Hub.settings.autoSaveTaught) "بدأ التسجيل: نفّذ المهمة بيدك ثم عُد إلى الشاشة الرئيسية، وسأحفظها وحدي."
                                    else "بدأ التسجيل: نفّذ المهمة بيدك، ثم اضغط الفقاعة الحمراء واختر «حفظ المهارة»."
                                else -> "فعّل خدمة إمكانية الوصول من الصفحة الرئيسية أولاً."
                            }
                            Toast.makeText(this@CommandActivity, msg, Toast.LENGTH_LONG).show()
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

private val quick = listOf(
    "اقرأ لي ما على الشاشة باختصار",
    "لخّص لي آخر الإشعارات",
    "شخّص الهاتف",
    "كم البطارية؟",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandSheet(
    askQuestion: String?, teachFirst: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit, onTeach: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var teach by rememberSaveable { mutableStateOf(teachFirst) }
    val focus = remember { FocusRequester() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val running = Hub.engine.state.value !is EngineState.Idle && askQuestion == null
    val lastResult = Hub.engine.lastResult.value

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            text = if (text.isBlank()) it else "$text $it"
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            askQuestion != null -> "الوكيل يسألك"
                            teach -> "علّمني مهمة بيدك"
                            else -> "وكيل"
                        },
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "إغلاق") }
                }
                when {
                    askQuestion != null -> Text(askQuestion, style = MaterialTheme.typography.bodyLarge, color = Brand.Amber)
                    teach -> Text(
                        "اكتب الجملة التي ستقولها لاحقاً لطلب هذه المهمة، ثم نفّذها بيدك مرة واحدة وأنا أسجّل خطواتك، وعُد بعدها إلى الشاشة الرئيسية لأحفظها. " +
                            "بعدها أنفّذها وحدي دون أي استهلاك. وما تكتبه أو تختاره من قائمة ويَرِد في جملتك (اسم جهة اتصال، نص رسالة) يصبح قابلاً للتغيير.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    running -> Text("هناك مهمة قيد التنفيذ الآن. أوقفها من الفقاعة أولاً.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    lastResult != null -> Surface(
                        onClick = { Hub.history.records.value.firstOrNull()?.let { ResultActivity.open(ctx, it.id) } },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("آخر نتيجة", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(lastResult.lines().first().take(120), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text("اضغط لعرضها كاملة ←", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = {
                        Text(
                            when {
                                askQuestion != null -> "اكتب ردّك…"
                                teach -> "مثال: أرسل واتساب إلى أحمد: أنا في الطريق"
                                else -> "ماذا تريد أن أفعل؟ يمكنك ذكر وقت: «بعد 10 دقائق…» أو «الساعة 8 مساءً…»"
                            }
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank() && !running) { if (teach) onTeach(text.trim()) else onSubmit(text.trim()) }
                    }),
                )
                if (askQuestion == null && !teach && text.isBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { q ->
                            Surface(onClick = { text = q }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text(q, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (teach) onTeach(text.trim()) else onSubmit(text.trim()) },
                        enabled = text.isNotBlank() && !running,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(if (teach) Icons.Rounded.FiberManualRecord else Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                askQuestion != null -> "إرسال الرد"
                                teach -> "ابدأ التسجيل"
                                else -> "تنفيذ"
                            }
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalIconButton(
                        onClick = {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                            try {
                                voice.launch(i)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, "الإدخال الصوتي غير متاح", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.Rounded.Mic, "إدخال صوتي") }
                }
                if (askQuestion == null && !running) {
                    TextButton(onClick = { teach = !teach }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (teach) "رجوع إلى الأوامر العادية" else "أو علّمني مهمة بيدك مرة واحدة — وأعيدها مجاناً")
                    }
                }
            }
        }
    }
}
