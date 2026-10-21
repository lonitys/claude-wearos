package com.lonitys.claudewatch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val project: String,
    val toolName: String,
    val summary: String,
    val detail: String,
    val shakeAllowed: Boolean,
    val created: Double,
) {
    companion object {
        fun from(o: JSONObject) = PermissionRequest(
            id = o.getString("id"),
            sessionId = o.optString("session_id"),
            project = o.optString("project"),
            toolName = o.optString("tool_name"),
            summary = o.optString("summary"),
            detail = o.optString("detail"),
            shakeAllowed = o.optBoolean("shake_allowed", true),
            created = o.optDouble("created", 0.0),
        )
    }
}

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val project: String,
    /** "assistant", "user" or "notification". */
    val role: String,
    val text: String,
    val awaitingReply: Boolean,
    val created: Double,
) {
    companion object {
        fun from(o: JSONObject) = ChatMessage(
            id = o.getString("id"),
            sessionId = o.optString("session_id"),
            project = o.optString("project"),
            role = o.optString("role", "assistant"),
            text = o.optString("text"),
            awaitingReply = o.optBoolean("awaiting_reply", false),
            created = o.optDouble("created", 0.0),
        )
    }
}

/** In-process state shared by the service, the notifications and the UI. */
object WatchState {
    private val _pending = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pending: StateFlow<List<PermissionRequest>> = _pending.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    /** Returns true if the request is new. */
    fun addPending(req: PermissionRequest): Boolean {
        var added = false
        _pending.update { list ->
            if (list.any { it.id == req.id }) list else {
                added = true
                (list + req).sortedBy { it.created }
            }
        }
        return added
    }

    fun removePending(id: String) = _pending.update { list -> list.filterNot { it.id == id } }

    fun replacePending(list: List<PermissionRequest>) {
        _pending.value = list.sortedBy { it.created }
    }

    /** Returns true if the message is new. */
    fun addMessage(msg: ChatMessage): Boolean {
        var added = false
        _messages.update { list ->
            if (list.any { it.id == msg.id }) list else {
                added = true
                (list + msg).takeLast(100)
            }
        }
        return added
    }

    fun replaceMessages(list: List<ChatMessage>) {
        _messages.value = list.takeLast(100)
    }

    /** Session of the most recent Claude message: where replies go by default. */
    fun latestSession(): String =
        _messages.value.lastOrNull { it.role != "user" && it.sessionId.isNotEmpty() }?.sessionId
            ?: _messages.value.lastOrNull()?.sessionId.orEmpty()
}
