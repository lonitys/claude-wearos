package com.lonitys.claudewatch

import android.content.Intent
import android.hardware.SensorManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay

/**
 * Full-screen approval prompt. While it is visible the accelerometer listens
 * for a shake; a shake starts a short countdown (tap to cancel) and then
 * approves the request.
 */
class ApprovalActivity : ComponentActivity() {
    private var focusId by mutableStateOf<String?>(null)
    private var shakeCount by mutableIntStateOf(0)
    private var detector: ShakeDetector? = null
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        focusId = intent.getStringExtra(EXTRA_ID)
        detector = ContextCompat.getSystemService(this, SensorManager::class.java)?.let {
            ShakeDetector(it, onShake = { shakeCount++ })
        }
        setContent {
            MaterialTheme {
                ApprovalScreen(
                    focusId = focusId,
                    shakeEnabled = prefs.shakeEnabled && detector != null,
                    shakeCount = shakeCount,
                    vibrate = ::vibrate,
                    decide = { id, decision ->
                        Actions.decide(applicationContext, id, decision)
                        focusId = null
                    },
                    close = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ID)?.let { focusId = it }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.shakeEnabled) detector?.start()
    }

    override fun onPause() {
        detector?.stop()
        super.onPause()
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}

private val Allow = Color(0xFF2F7D4F)
private val Deny = Color(0xFFB3261E)
private val Accent = Color(0xFFD97757)
private const val COUNTDOWN_MS = 1500

@Composable
private fun ApprovalScreen(
    focusId: String?,
    shakeEnabled: Boolean,
    shakeCount: Int,
    vibrate: (LongArray) -> Unit,
    decide: (String, String) -> Unit,
    close: () -> Unit,
) {
    val pending by WatchState.pending.collectAsState()
    val current = pending.firstOrNull { it.id == focusId } ?: pending.firstOrNull()

    // Nothing (left) to answer: close shortly after, so a late resolve doesn't flash.
    LaunchedEffect(current == null) {
        if (current == null) {
            delay(800)
            close()
        }
    }
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Erledigt") }
        return
    }

    var countingDown by remember(current.id) { mutableStateOf(false) }
    val progress = remember(current.id) { Animatable(0f) }
    val canShake = shakeEnabled && current.shakeAllowed

    // Only shakes made while this request is on screen count.
    val shakeBaseline = remember(current.id) { shakeCount }
    LaunchedEffect(shakeCount) {
        if (shakeCount > shakeBaseline && canShake && !countingDown) {
            vibrate(longArrayOf(0, 60))
            countingDown = true
        }
    }
    LaunchedEffect(countingDown, current.id) {
        if (countingDown) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(COUNTDOWN_MS, easing = LinearEasing))
            vibrate(longArrayOf(0, 40, 80, 40))
            decide(current.id, "allow")
        }
    }

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = current.toolName.ifBlank { "Freigabe" },
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            if (current.project.isNotBlank()) {
                item { Text(current.project, fontSize = 12.sp, color = Color.LightGray) }
            }
            if (current.summary.isNotBlank()) {
                item {
                    Text(
                        text = current.summary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF262626), MaterialTheme.shapes.small)
                            .padding(8.dp),
                    )
                }
            }
            if (current.detail.isNotBlank()) {
                item {
                    Text(
                        text = current.detail,
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text(
                    text = when {
                        canShake -> "Schütteln = Erlauben"
                        !current.shakeAllowed -> "⚠ Riskant – bitte tippen"
                        else -> "Tippe zum Antworten"
                    },
                    fontSize = 12.sp,
                    color = if (current.shakeAllowed) Color.Gray else Accent,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { decide(current.id, "deny") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Deny),
                    ) { Text("✕", fontSize = 22.sp) }
                    Button(
                        onClick = { decide(current.id, "allow") },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Allow),
                    ) { Text("✓", fontSize = 22.sp) }
                }
            }
            if (pending.size > 1) {
                item { Text("+${pending.size - 1} weitere", fontSize = 12.sp, color = Color.Gray) }
            }
        }

        if (countingDown) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6000000))
                    .clickable { countingDown = false },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = progress.value,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    indicatorColor = Allow,
                    strokeWidth = 8.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✓", fontSize = 36.sp, color = Allow)
                    Text("Wird erlaubt…", fontWeight = FontWeight.Bold)
                    Text("Tippen = Abbrechen", fontSize = 12.sp, color = Color.LightGray)
                    Box(Modifier.size(4.dp))
                }
            }
        }
    }
}
