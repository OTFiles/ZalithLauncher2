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
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.movtery.zalithlauncher.utils.logging.Logger

private const val TAG = "GameRuntime"

/**
 * 进程生命周期管理。
 *
 * Minecraft 的 JVM 是内嵌在本进程里的，而 JVM 在一个进程内只能创建一次，
 * 游戏的 System.exit() 又会被 exit hook 接管并结束整个进程。
 * 因此"游戏退出后还想再玩/换版本"只能靠重启进程实现：
 *  1. 记下想启动的版本（[ServerSettings.pendingLaunch]）
 *  2. 主动结束本进程
 *  3. 依赖 START_STICKY 的前台服务被系统重新拉起
 *  4. [ServerService] 起来后读取 pendingLaunch 自动继续
 */
object GameRuntime {
    /**
     * 重启当前进程
     */
    fun restartProcess(context: Context) {
        Logger.info(TAG, "Restarting the server process")
        runCatching {
            //先把服务标记为需要恢复，START_STICKY 才能把进程拉回来
            ServerSettings.serverEnabled = true
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val intent = Intent(context, ServerService::class.java)
                    .setAction(ServerService.ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
            Process.killProcess(Process.myPid())
        }, 300)
    }

    /**
     * 清空"下次启动"的标记，返回要启动的版本
     */
    fun consumePendingLaunch(): String {
        val pending = ServerSettings.pendingLaunch
        if (pending.isNotEmpty()) {
            ServerSettings.pendingLaunch = ""
        }
        return pending
    }
}
