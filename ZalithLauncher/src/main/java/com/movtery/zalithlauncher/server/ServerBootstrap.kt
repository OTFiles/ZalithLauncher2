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

import android.content.Context
import com.movtery.zalithlauncher.components.Components
import com.movtery.zalithlauncher.components.UnpackComponentsTask
import com.movtery.zalithlauncher.components.jre.Jre
import com.movtery.zalithlauncher.components.jre.UnpackJnaTask
import com.movtery.zalithlauncher.components.jre.UnpackJreTask
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.loadAllSettings
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "ServerBootstrap"

/**
 * 服务端冷启动时补齐启动器在 SplashActivity 里做的初始化工作。
 *
 * 服务端没有引导界面，被开机广播拉起来时必须自己把 JRE/LWJGL/JNA 解包出来，
 * 否则游戏根本起不来。
 */
object ServerBootstrap {
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 完成应用级初始化：设置、通知渠道、目录
     */
    fun initApp(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            loadAllSettings(context)
            com.movtery.zalithlauncher.notification.NotificationManager.initManager(context)
            com.movtery.zalithlauncher.utils.checkStoragePermissionsForInit(context)
        }.onFailure {
            Logger.error(TAG, "Application bootstrap failed", it)
        }
        //让桌面图标的开关在开机/更新后依然生效
        runCatching {
            setLauncherIconVisible(context, ServerSettings.showLauncherIcon)
        }.onFailure {
            Logger.warning(TAG, "Failed to apply the launcher icon state", it)
        }
    }

    /**
     * 确保所有需要解包的组件就绪
     */
    suspend fun ensureComponents(context: Context) {
        initApp(context)

        //存储权限：安卓 11 以上需要"所有文件访问权限"，服务端通过设置页申请
        logStorageAccess()

        val tasks = mutableListOf<suspend () -> Unit>()

        Components.entries.forEach { component ->
            val task = UnpackComponentsTask(context, component)
            if (!task.isCheckFailed()) {
                tasks.add {
                    if (task.checkState() != com.movtery.zalithlauncher.components.InstallableItem.State.FINISHED) {
                        Logger.info(TAG, "Unpacking component: ${component.component}")
                        task.run()
                    }
                }
            }
        }
        Jre.entries.forEach { jre ->
            val task = UnpackJreTask(context, jre)
            if (!task.isCheckFailed()) {
                tasks.add {
                    if (task.checkState() != com.movtery.zalithlauncher.components.InstallableItem.State.FINISHED) {
                        Logger.info(TAG, "Unpacking JRE: ${jre.jreName}")
                        task.run()
                        //必须 reload 才能让 RuntimesManager 看到新解包的环境
                        RuntimesManager.getRuntimes(true)
                    }
                }
            }
        }
        val jnaTask = UnpackJnaTask(context)
        if (!jnaTask.isCheckFailed()) {
            tasks.add {
                if (jnaTask.checkState() != com.movtery.zalithlauncher.components.InstallableItem.State.FINISHED) {
                    Logger.info(TAG, "Unpacking JNA")
                    jnaTask.run()
                }
            }
        }

        tasks.forEach { task ->
            runCatching { task() }.onFailure {
                Logger.error(TAG, "Component unpack failed", it)
            }
        }

        if (AllSettings.javaRuntime.getValue().isEmpty()) {
            AllSettings.javaRuntime.save(Jre.JRE_8.jreName)
        }
        ServerSettings.applyResolutionPolicy()
        Logger.info(TAG, "All components are ready")
    }

    /**
     * 记录存储权限状态。
     *
     * 安卓 11 及以上必须由用户在设置里手动授予"所有文件访问权限"，
     * 这里只做记录，申请入口在 [ServerSettingsActivity]。
     */
    private fun logStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val granted = android.os.Environment.isExternalStorageManager()
            Logger.info(TAG, "All-files access granted: $granted")
            if (!granted) {
                Logger.warning(
                    TAG,
                    "Minecraft cannot read its files without MANAGE_EXTERNAL_STORAGE; " +
                            "open the settings screen and grant it."
                )
            }
        }
    }
}
