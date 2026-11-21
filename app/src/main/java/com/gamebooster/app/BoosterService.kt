package com.gamebooster.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gamebooster.app.core.OverlayText
import com.gamebooster.app.overlay.OverlayController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun requestForegroundService(startRequest: () -> Unit): Result<Unit> = try {
    startRequest()
    Result.success(Unit)
} catch (e: Exception) {
    Result.failure(e)
}

/** Foreground service whose only job is to keep the process (and BoosterEngine's monitor) alive while connected. */
class BoosterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null
    private var overlayJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayJob?.cancel(); overlay?.hide(); scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Connection status", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Game Booster")
            .setContentText("Tunnel active – monitoring connection")
            .setContentIntent(open).setOngoing(true).build()
        val promotion = requestForegroundService {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }
        if (promotion.isFailure) {
            (application as? BoosterApp)?.engine?.onForegroundServiceStartFailed(promotion.exceptionOrNull())
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (overlayJob == null) {
            val ov = OverlayController(this).also { overlay = it }
            val engine = (application as BoosterApp).engine
            overlayJob = scope.launch {
                engine.state.map { s ->
                    OverlayText.build(s.overlayEnabled, s.connected, s.reconnecting, s.overlayPermissionGranted, s.live, s.activeServer?.name)
                }.distinctUntilChanged().collect { m ->
                    if (m.visible) { ov.show(); ov.update(m.text, m.level) } else ov.hide()
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val ID = 1
        fun start(ctx: Context): Result<Unit> = requestForegroundService {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BoosterService::class.java))
            Unit
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, BoosterService::class.java)) } }
    }
}
