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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "ServerBootReceiver"

/**
 * 开机自启 / 应用更新后自启。
 *
 * 注意：安卓 10 起系统对后台启动前台服务有限制，如果用户在系统设置里禁止了
 * 自启动，这里会静默失败——但这不影响用户在设置页手动启动。
 */
class ServerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        GlobalContext = context.applicationContext
        ServerBootstrap.initApp(context)
        if (!ServerSettings.autoStartOnBoot) {
            Logger.info(TAG, "Auto start disabled, ignoring $action")
            return
        }
        Logger.info(TAG, "Boot completed, starting the stream server")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(
                    Intent(context, ServerService::class.java).setAction(ServerService.ACTION_START)
                )
            } else {
                context.startService(
                    Intent(context, ServerService::class.java).setAction(ServerService.ACTION_START)
                )
            }
        }.onFailure {
            Logger.error(TAG, "Failed to auto start the server", it)
        }
    }
}
