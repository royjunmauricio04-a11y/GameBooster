package com.gamebooster.app.data

import android.content.Context
import com.gamebooster.app.core.HistoryCodec
import com.gamebooster.app.core.SessionRecord
import java.io.File

/**
 * Local session history (JSON lines, newest last, capped). Holds only package/server names and ping numbers,
 * so it lives in plain app-private storage rather than the encrypted prefs. Call from a background thread.
 */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.jsonl")

    @Synchronized fun load(): List<SessionRecord> = runCatching {
        if (!file.exists()) emptyList()
        else file.readLines().mapNotNull { runCatching { HistoryCodec.decode(it) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized fun append(r: SessionRecord) {
        runCatching {
            val all = (load() + r).takeLast(MAX)
            val tmp = File(file.parentFile, "history.jsonl.tmp")
            tmp.writeText(all.joinToString("\n") { HistoryCodec.encode(it) } + "\n")
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    @Synchronized fun clear() { runCatching { file.delete() } }

    private companion object { const val MAX = 200 }
}
