package com.lonitys.claudewatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Handles the Allow/Deny/Reply buttons on notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        when (intent.action) {
            ACTION_DECIDE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return pending.finish()
                val decision = intent.getStringExtra(EXTRA_DECISION) ?: return pending.finish()
                Actions.decide(app, id, decision) { pending.finish() }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                val session = intent.getStringExtra(EXTRA_SESSION).orEmpty()
                if (text.isNullOrBlank()) return pending.finish()
                Notifier.cancelChat(app)
                Actions.reply(app, session, text) { pending.finish() }
            }
            else -> pending.finish()
        }
    }

    companion object {
        const val ACTION_DECIDE = "com.lonitys.claudewatch.DECIDE"
        const val ACTION_REPLY = "com.lonitys.claudewatch.REPLY"
        const val EXTRA_ID = "id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_SESSION = "session"
    }
}

/** Sends decisions and replies to the relay off the main thread. */
object Actions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun decide(context: Context, id: String, decision: String, done: () -> Unit = {}) {
        val req = WatchState.pending.value.firstOrNull { it.id == id }
        WatchState.removePending(id)
        Notifier.cancelPermission(context, id)
        send(context, "Entscheidung", done, onError = {
            // Put it back so it can be answered again.
            if (req != null && WatchState.addPending(req)) Notifier.showPermission(context, req)
        }) { Prefs(context).client().decide(id, decision) }
    }

    fun reply(context: Context, sessionId: String, text: String, done: () -> Unit = {}) {
        send(context, "Antwort", done) { Prefs(context).client().reply(sessionId, text) }
    }

    private fun send(
        context: Context,
        what: String,
        done: () -> Unit,
        onError: () -> Unit = {},
        block: () -> Unit,
    ) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("Actions", "$what failed", e)
                onError()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "$what nicht gesendet: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                done()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.enabled && prefs.configured) RelayService.start(context)
    }
}
