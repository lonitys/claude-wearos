package com.lonitys.claudewatch

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.input.RemoteInputIntentHelper

private enum class Screen { Home, Chat, Settings }

private val Accent = Color(0xFFD97757)

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannels(this)
        val prefs = Prefs(this)
        if (prefs.enabled && prefs.configured) RelayService.start(this)
        if (intent.getBooleanExtra(EXTRA_CHAT, false)) screen = Screen.Chat
        setContent {
            MaterialTheme {
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (screen) {
                    Screen.Home -> HomeScreen(prefs, navigate = { screen = it })
                    Screen.Chat -> ChatScreen()
                    Screen.Settings -> SettingsScreen(prefs)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHAT, false)) screen = Screen.Chat
    }

    companion object {
        const val EXTRA_CHAT = "chat"
    }
}

/** Opens the system text/voice input and passes the entered text to [onText]. */
@Composable
private fun rememberTextInput(label: String, onText: (String) -> Unit): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(KEY_INPUT)?.toString()
        if (!text.isNullOrBlank()) onText(text.trim())
    }
    return { initial ->
        val input = RemoteInput.Builder(KEY_INPUT)
            .setLabel(label)
            .apply { if (initial.isNotEmpty()) setChoices(arrayOf(initial)) }
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        launcher.launch(intent)
    }
}

private const val KEY_INPUT = "input"

@Composable
private fun WatchList(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun HomeScreen(prefs: Prefs, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val connected by WatchState.connected.collectAsState()
    val pending by WatchState.pending.collectAsState()
    val messages by WatchState.messages.collectAsState()
    var enabled by remember { mutableStateOf(prefs.enabled) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    WatchList {
        item { ListHeader { Text("Claude Watch", color = Accent) } }
        item {
            ToggleChip(
                checked = enabled,
                onCheckedChange = { on ->
                    if (on && !prefs.configured) {
                        navigate(Screen.Settings)
                    } else {
                        enabled = on
                        prefs.enabled = on
                        if (on) RelayService.start(context) else RelayService.stop(context)
                    }
                },
                label = { Text("Verbindung") },
                secondaryLabel = {
                    Text(
                        when {
                            !enabled -> "Aus"
                            connected -> "Verbunden"
                            else -> "Verbinde…"
                        }
                    )
                },
                toggleControl = { androidx.wear.compose.material.Switch(checked = enabled) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(pending, key = { it.id }) { req ->
            Chip(
                onClick = {
                    context.startActivity(
                        Intent(context, ApprovalActivity::class.java).putExtra(ApprovalActivity.EXTRA_ID, req.id)
                    )
                },
                label = { Text(req.toolName, maxLines = 1) },
                secondaryLabel = { Text(req.summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ChipDefaults.primaryChipColors(backgroundColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { navigate(Screen.Chat) },
                label = { Text("Chat") },
                secondaryLabel = {
                    Text(messages.lastOrNull()?.text ?: "Noch keine Nachrichten", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { navigate(Screen.Settings) },
                label = { Text("Einstellungen") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChatScreen() {
    val context = LocalContext.current
    val messages by WatchState.messages.collectAsState()
    val send: (String) -> Unit = { text -> Actions.reply(context, WatchState.latestSession(), text) }
    val input = rememberTextInput("Antwort an Claude", send)

    WatchList {
        item { ListHeader { Text("Chat", color = Accent) } }
        item {
            Chip(
                onClick = { input("") },
                label = { Text("Antworten 🎤") },
                colors = ChipDefaults.primaryChipColors(backgroundColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        items(Notifier.QUICK_REPLIES.toList()) { text ->
            CompactChip(onClick = { send(text) }, label = { Text(text) })
        }
        if (messages.isEmpty()) {
            item { Text("Noch keine Nachrichten", color = Color.Gray, textAlign = TextAlign.Center) }
        }
        // Newest first: the latest answer is right under the reply buttons.
        items(messages.asReversed(), key = { it.id }) { msg ->
            Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when (msg.role) {
                        "user" -> "Du"
                        "notification" -> "Hinweis · ${msg.project}"
                        else -> "Claude · ${msg.project}"
                    }.trimEnd(' ', '·'),
                    fontSize = 11.sp,
                    color = if (msg.role == "user") Color.LightGray else Accent,
                )
                Text(msg.text, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun SettingsScreen(prefs: Prefs) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(prefs.url) }
    var token by remember { mutableStateOf(prefs.token) }
    var shake by remember { mutableStateOf(prefs.shakeEnabled) }
    val restart = { if (prefs.enabled) RelayService.start(context) }
    val editUrl = rememberTextInput("Relay-URL") { url = it; prefs.url = it; restart() }
    val editToken = rememberTextInput("Token") { token = it; prefs.token = it; restart() }

    WatchList {
        item { ListHeader { Text("Einstellungen", color = Accent) } }
        item {
            Chip(
                onClick = { editUrl(url) },
                label = { Text("Relay-URL") },
                secondaryLabel = { Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { editToken("") },
                label = { Text("Token") },
                secondaryLabel = { Text(if (token.isBlank()) "nicht gesetzt" else "••••" + token.takeLast(3)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = shake,
                onCheckedChange = { shake = it; prefs.shakeEnabled = it },
                label = { Text("Schütteln = Erlauben") },
                toggleControl = { androidx.wear.compose.material.Switch(checked = shake) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
