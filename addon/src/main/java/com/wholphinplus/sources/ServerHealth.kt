package com.wholphinplus.sources

import android.content.Context
import com.wholphinplus.sources.core.ServerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * How each extra server answered its last lookup, learned only from the lookups Orca+ already
 * makes (owner, 2026-10-09: show which servers had nothing and why, notify about an offline
 * server, "don't spam ping… I don't want to abuse the API"). Nothing here sends a request.
 *
 * A server in trouble rests for [Trouble.restMs]: lookups skip it and report the remembered
 * reason, instead of asking a dead server again on every Play. A new sign-in (its
 * lastConnectedAt changes) or any good answer ends the rest at once.
 */
class ServerHealth(
    context: Context,
) {
    enum class Trouble(
        val reason: String,
        val restMs: Long,
    ) {
        SIGN_IN("sign-in expired", 30 * 60_000L),
        OFFLINE("offline or out of reach", 5 * 60_000L),
        ERROR("server error", 5 * 60_000L),

        // Still answering, just late: asked again next time
        SLOW("too slow to answer", 0L),
    }

    data class Check(
        val trouble: Trouble?,
        val at: Long,
        // The sign-in it was about: a new one starts fresh
        val signIn: String,
    )

    private val prefs = context.getSharedPreferences("wholphinplus_server_health", Context.MODE_PRIVATE)
    // Kept across restarts, so reopening the app doesn't ask a resting server again
    private val _checks =
        MutableStateFlow(
            prefs.all.mapNotNull { (k, v) ->
                val id = k.removePrefix(CHECK).takeIf { k.startsWith(CHECK) } ?: return@mapNotNull null
                val parts = (v as? String)?.split('|')?.takeIf { it.size == 3 } ?: return@mapNotNull null
                val trouble = Trouble.entries.firstOrNull { it.name == parts[0] }
                id to Check(trouble, parts[1].toLongOrNull() ?: return@mapNotNull null, parts[2])
            }.toMap(),
        )
    val checks: StateFlow<Map<String, Check>> = _checks.asStateFlow()


    private fun signIn(c: ServerConnection) = c.lastConnectedAt.toString()

    /** The remembered trouble when [c] is resting, so the lookup can skip it. */
    fun resting(c: ServerConnection): Check? =
        _checks.value[c.connectionId]?.takeIf {
            it.trouble != null && it.signIn == signIn(c) && System.currentTimeMillis() - it.at < it.trouble.restMs
        }

    fun ok(c: ServerConnection) {
        val was = _checks.value[c.connectionId]
        _checks.update { it + (c.connectionId to Check(null, System.currentTimeMillis(), signIn(c))) }
        // Back to normal: a later outage is news again (written only when it changes)
        if (was?.trouble != null || was == null) prefs.edit().remove(c.connectionId).remove(CHECK + c.connectionId).apply()
        // The Message Center's line for it says it's back (quietly: the trouble was the news)
        if (was?.trouble != null && was.trouble != Trouble.SLOW) {
            Inbox.post(Inbox.Kind.SERVERS, "${c.label} is back", "Its copies show up when you press Play again.", Inbox.Level.GOOD, key = "server:${c.connectionId}", popUp = false, unread = false)
        }
    }

    fun failed(
        c: ServerConnection,
        trouble: Trouble,
    ) {
        val now = System.currentTimeMillis()
        _checks.update { it + (c.connectionId to Check(trouble, now, signIn(c))) }
        Timber.w("Server %s: %s", c.label, trouble.reason)
        if (trouble == Trouble.SLOW) return
        prefs.edit().putString(CHECK + c.connectionId, "${trouble.name}|$now|${signIn(c)}").apply()
        // Once per server and problem a day, kept across restarts
        val said = prefs.getString(c.connectionId, null)?.split('|')
        if (said != null && said[0] == trouble.name && now - (said.getOrNull(1)?.toLongOrNull() ?: 0L) < NOTICE_AGAIN_MS) return
        prefs.edit().putString(c.connectionId, "${trouble.name}|$now").apply()
        Inbox.post(
            Inbox.Kind.SERVERS,
            when (trouble) {
                Trouble.SIGN_IN -> "${c.label}: sign-in expired"
                Trouble.OFFLINE -> "${c.label} is offline"
                else -> "${c.label} answered with an error"
            },
            notice(c.label, trouble),
            Inbox.Level.WARN,
            key = "server:${c.connectionId}",
            action = Inbox.Action.Settings("SOURCES", if (trouble == Trouble.SIGN_IN) "Sign in again" else "Check it"),
        )
    }

    companion object {
        private const val NOTICE_AGAIN_MS = 24 * 60 * 60_000L
        private const val CHECK = "check:"

        fun notice(
            label: String,
            trouble: Trouble,
        ): String =
            when (trouble) {
                Trouble.SIGN_IN -> "$label: sign-in expired. Sign in again in Settings → Servers & Copies → Extra servers."
                Trouble.OFFLINE -> "$label is offline or out of reach. Its copies are left out for now."
                Trouble.ERROR -> "$label answered with an error. Its copies are left out for now."
                Trouble.SLOW -> "$label is answering slowly."
            }
    }
}
