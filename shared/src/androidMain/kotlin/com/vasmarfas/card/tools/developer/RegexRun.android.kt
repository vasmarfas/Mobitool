package com.vasmarfas.card.tools.developer

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.vasmarfas.card.core.AppContextHolder
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class RegexRequest(
    val pattern: String,
    val text: String,
    val ignoreCase: Boolean,
    val multiline: Boolean,
    val dotAll: Boolean,
    val replacement: String?,
)

private const val REQUEST_FILE = "regex-request.json"
private const val RESULT_FILE = "regex-result.json"

private val regexMutex = Mutex()

actual suspend fun runRegex(
    pattern: String,
    text: String,
    ignoreCase: Boolean,
    multiline: Boolean,
    dotAll: Boolean,
    replacement: String?,
): RegexRunResult = regexMutex.withLock {
    val context = AppContextHolder.context
    val request = RegexRequest(pattern, text, ignoreCase, multiline, dotAll, replacement)
    withContext(Dispatchers.IO) {
        File(context.cacheDir, REQUEST_FILE).writeText(Json.encodeToString(RegexRequest.serializer(), request))
    }
    val service = CompletableDeferred<Messenger>()
    val reply = CompletableDeferred<Unit>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service.complete(Messenger(binder))
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    context.bindService(Intent(context, RegexService::class.java), connection, Context.BIND_AUTO_CREATE)
    var answered = false
    try {
        val callback = Messenger(Handler(Looper.getMainLooper()) { reply.complete(Unit) })
        service.await().send(Message.obtain().apply { replyTo = callback })
        answered = withTimeoutOrNull(RegexTester.TIME_LIMIT_MS.milliseconds) { reply.await() } != null
    } finally {
        context.unbindService(connection)
        if (!answered) {
            val name = context.packageName + ":regex"
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.processName == name }
                ?.let { Process.killProcess(it.pid) }
        }
    }
    if (answered) {
        withContext(Dispatchers.IO) {
            Json.decodeFromString(RegexRunResult.serializer(), File(context.cacheDir, RESULT_FILE).readText())
        }
    } else {
        RegexRunResult(text, emptyList(), null, null, timedOut = true)
    }
}

class RegexService : Service() {
    private val thread = HandlerThread("regex").apply { start() }
    private val messenger = Messenger(Handler(thread.looper) { message ->
        val request = Json.decodeFromString(RegexRequest.serializer(), File(cacheDir, REQUEST_FILE).readText())
        val result = with(request) { RegexTester.run(pattern, text, ignoreCase, multiline, dotAll, replacement) }
        File(cacheDir, RESULT_FILE).writeText(Json.encodeToString(RegexRunResult.serializer(), result))
        message.replyTo.send(Message.obtain())
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quit()
    }
}
