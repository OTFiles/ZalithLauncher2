/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.movtery.streamcore.StreamProtocol
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "ServerService"

/** 前台服务通知 ID */
const val NOTIFICATION_ID_SERVER = 2001
/** 通知渠道 */
const val CHANNEL_ID_SERVER = "zl2.server.stream"

/**
 * 串流服务端前台服务。
 *
 * 这个 Service 是整个服务端的心脏：
 *  - 让进程常驻，进入前台避免被系统回收
 *  - 持有唤醒锁与 Wi-Fi 锁，保证屏幕熄灭后仍然编码/传输
 *  - 维护 [ServerStreamEngine] 的生命周期
 */
class ServerService : Service() {
    companion object {
        @Volatile
        var instance: ServerService? = null
            private set

        const val ACTION_START = "com.movtery.zalithlauncher.server.action.START"
        const val ACTION_STOP = "com.movtery.zalithlauncher.server.action.STOP"
        const val ACTION_REFRESH = "com.movtery.zalithlauncher.server.action.REFRESH"

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }

        fun refresh(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_REFRESH))
        }

        fun isRunning(): Boolean = instance != null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var lastError: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        GlobalContext = applicationContext
        ServerBootstrap.initApp(this)
        createChannel()
        startForegroundCompat(buildNotification())
        ServerStreamEngine.instance.onStatusChanged = { status ->
            if (status.error.isNotEmpty()) lastError = status.error
            refreshNotification()
        }
        ServerStreamEngine.instance.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                ServerStreamEngine.instance.stop()
                ServerStreamEngine.instance.start()
            }
            else -> Unit
        }
        startForegroundCompat(buildNotification())
        acquireLocks()
        //自动拉起上次运行的游戏
        if (intent?.action == ACTION_START && ServerSettings.autoLaunchGame) {
            val last = ServerSettings.lastVersion
            if (last.isNotEmpty() && !GameSession.isRunning()) {
                Logger.info(TAG, "Auto launching $last")
                GameSession.launch(this, last)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        //用户从最近任务划掉界面时不要让服务跟着死掉
        if (ServerSettings.keepAlive) {
            runCatching {
                startService(Intent(this, ServerService::class.java).setAction(ACTION_START))
            }
        }
    }

    override fun onDestroy() {
        instance = null
        ServerStreamEngine.instance.onStatusChanged = null
        ServerStreamEngine.instance.stop()
        releaseLocks()
        super.onDestroy()
    }

    // region 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID_SERVER,
            getString(R.string.server_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.server_notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val engine = ServerStreamEngine.instance
        val state = GameSession.state.value
        val listening = engine.isRunning()
        val addresses = engine.localAddresses().firstOrNull() ?: "0.0.0.0"
        val port = ServerSettings.port
        val summary = when {
            !listening -> getString(R.string.server_notification_stopped)
            state.state == StreamProtocol.STATE_RUNNING ->
                getString(R.string.server_notification_playing, state.currentVersion.ifEmpty { "Minecraft" })
            else -> getString(R.string.server_notification_idle, "${state.fps}fps")
        }
        val detail = if (listening) "$addresses:$port · ${engine.clientCount()} client(s)" else lastError

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("com.movtery.zalithlauncher.server.ServerSettingsActivity")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_SERVER)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(getString(R.string.app_server_name))
            .setContentText("$summary · $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$detail"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.server_notification_stop), stopIntent)
            .build()
    }

    private fun refreshNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID_SERVER, buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_SERVER,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID_SERVER, notification)
            } else {
                startForeground(NOTIFICATION_ID_SERVER, notification)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to enter foreground", e)
            stopSelf()
        }
    }
    // endregion

    // region 保活

    private fun acquireLocks() {
        if (!ServerSettings.keepAlive) return
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Zalith:StreamServer")
                wakeLock?.setReferenceCounted(false)
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire()
        }.onFailure {
            Logger.warning(TAG, "Failed to acquire the wake lock", it)
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                wifiLock = wm?.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Zalith:StreamWifi"
                )
                wifiLock?.setReferenceCounted(false)
            }
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        }.onFailure {
            Logger.warning(TAG, "Failed to acquire the Wi-Fi lock", it)
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }
    // endregion
}
