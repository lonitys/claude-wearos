package com.lonitys.claudewatch

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Keeps a long-poll connection to the relay open while enabled, and turns
 * relay events into notifications and the approval screen.
 */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        ServiceCompat.startForeground(
            this, Notifier.SERVICE_ID, Notifier.serviceNotification(this, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        scope.launch {
            WatchState.connected.distinctUntilChanged().collect { connected ->
                getSystemService(NotificationManager::class.java)
                    .notify(Notifier.SERVICE_ID, Notifier.serviceNotification(this@RelayService, connected))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (Re)start the loop so changed settings take effect.
        loop?.cancel()
        loop = scope.launch { pollForever() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        WatchState.setConnected(false)
        super.onDestroy()
    }

    private suspend fun pollForever() {
        val prefs = Prefs(this)
        var backoff = 2_000L
        while (scope.isActive) {
            try {
                val client = prefs.client()
                var last = applyState(client.state())
                WatchState.setConnected(true)
                backoff = 2_000L
                while (scope.isActive) {
                    val res = client.events(last)
                    val newLast = res.getLong("last")
                    if (newLast < last) break // relay restarted: resync from /state
                    val events = res.getJSONArray("events")
                    for (i in 0 until events.length()) handleEvent(events.getJSONObject(i))
                    last = newLast
                }
            } catch (e: Exception) {
                Log.w(TAG, "relay connection failed: ${e.message}")
                WatchState.setConnected(false)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun applyState(state: JSONObject): Long {
        val perms = state.getJSONArray("permissions")
        val pending = (0 until perms.length()).map { PermissionRequest.from(perms.getJSONObject(it)) }
        val known = WatchState.pending.value.map { it.id }.toSet()
        // Drop notifications for requests that were resolved while we were offline.
        (known - pending.map { it.id }.toSet()).forEach { Notifier.cancelPermission(this, it) }
        WatchState.replacePending(pending)
        pending.filter { it.id !in known }.forEach { alertPermission(it) }

        val msgs = state.getJSONArray("messages")
        WatchState.replaceMessages((0 until msgs.length()).map { ChatMessage.from(msgs.getJSONObject(it)) })
        return state.getLong("last")
    }

    private fun handleEvent(event: JSONObject) {
        val data = event.getJSONObject("data")
        when (event.getString("type")) {
            "permission" -> {
                val req = PermissionRequest.from(data)
                if (WatchState.addPending(req)) alertPermission(req)
            }
            "resolved" -> {
                val id = data.getString("id")
                WatchState.removePending(id)
                Notifier.cancelPermission(this, id)
            }
            "message" -> {
                val msg = ChatMessage.from(data)
                if (WatchState.addMessage(msg) && msg.role != "user") Notifier.showMessage(this, msg)
            }
        }
    }

    private fun alertPermission(req: PermissionRequest) {
        Notifier.showPermission(this, req)
    }

    companion object {
        private const val TAG = "RelayService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
