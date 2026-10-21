package com.lonitys.claudewatch

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

object Notifier {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_PERMISSION = "permission"
    const val CHANNEL_CHAT = "chat"
    const val SERVICE_ID = 1
    private const val CHAT_ID = 2
    const val KEY_REPLY = "reply_text"
    val QUICK_REPLIES = arrayOf("Ja, weiter", "Nein", "Stopp", "Mach einen Commit")

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, "Verbindung", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CHANNEL_PERMISSION, "Freigaben", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 120, 250)
                },
                NotificationChannel(CHANNEL_CHAT, "Chat", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }

    fun serviceNotification(context: Context, connected: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_claude)
            .setContentTitle("Claude Watch")
            .setContentText(if (connected) "Verbunden" else "Verbinde…")
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    @SuppressLint("MissingPermission")
    fun showPermission(context: Context, req: PermissionRequest) {
        val approve = Intent(context, ApprovalActivity::class.java)
            .putExtra(ApprovalActivity.EXTRA_ID, req.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fullScreen = PendingIntent.getActivity(
            context, req.id.hashCode(), approve,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_PERMISSION)
            .setSmallIcon(R.drawable.ic_claude)
            .setContentTitle("${req.toolName} · ${req.project}".trim(' ', '·'))
            .setContentText(req.summary.ifBlank { req.detail })
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(req.summary, req.detail).filter { it.isNotBlank() }.joinToString("\n\n")))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Ablehnen", decisionIntent(context, req.id, "deny"))
            .addAction(0, "Erlauben", decisionIntent(context, req.id, "allow"))
            .build()
        NotificationManagerCompat.from(context).notify(req.id, 0, notification)
    }

    fun cancelPermission(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(id, 0)
    }

    @SuppressLint("MissingPermission")
    fun showMessage(context: Context, msg: ChatMessage) {
        val replyInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Antwort an Claude")
            .setChoices(QUICK_REPLIES)
            .build()
        val replyIntent = PendingIntent.getBroadcast(
            context, msg.id.hashCode(),
            Intent(context, ActionReceiver::class.java)
                .setAction(ActionReceiver.ACTION_REPLY)
                .putExtra(ActionReceiver.EXTRA_SESSION, msg.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val title = if (msg.role == "notification") "Claude wartet" else "Claude · ${msg.project}".trim(' ', '·')
        val notification = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_claude)
            .setContentTitle(title)
            .setContentText(msg.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.text))
            .setContentIntent(openApp(context, chat = true))
            .setAutoCancel(true)
            .addAction(
                NotificationCompat.Action.Builder(0, "Antworten", replyIntent)
                    .addRemoteInput(replyInput)
                    .setAllowGeneratedReplies(true)
                    .build()
            )
            .build()
        NotificationManagerCompat.from(context).notify(CHAT_ID, notification)
    }

    fun cancelChat(context: Context) = NotificationManagerCompat.from(context).cancel(CHAT_ID)

    private fun decisionIntent(context: Context, id: String, decision: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, (id + decision).hashCode(),
            Intent(context, ActionReceiver::class.java)
                .setAction(ActionReceiver.ACTION_DECIDE)
                .putExtra(ActionReceiver.EXTRA_ID, id)
                .putExtra(ActionReceiver.EXTRA_DECISION, decision),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openApp(context: Context, chat: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            context, if (chat) 11 else 10,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CHAT, chat)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
