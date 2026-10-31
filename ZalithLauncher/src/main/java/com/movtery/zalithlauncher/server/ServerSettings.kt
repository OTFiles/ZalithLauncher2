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
import android.os.Build
import android.provider.Settings
import com.movtery.streamcore.StreamProtocol
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.setting.enums.ResolutionRule
import com.movtery.zalithlauncher.setting.launcherMMKV

/**
 * 服务端专属设置，与启动器原有设置分开存放，避免互相污染
 */
object ServerSettings {
    private const val KEY_PORT = "stream_server_port"
    private const val KEY_PASSWORD = "stream_server_password"
    private const val KEY_WIDTH = "stream_server_width"
    private const val KEY_HEIGHT = "stream_server_height"
    private const val KEY_BITRATE = "stream_server_bitrate_kbps"
    private const val KEY_FPS = "stream_server_fps"
    private const val KEY_AUTO_START = "stream_server_auto_start"
    private const val KEY_KEEP_ALIVE = "stream_server_keep_alive"
    private const val KEY_SHOW_ICON = "stream_server_show_icon"
    private const val KEY_AUTO_LAUNCH_GAME = "stream_server_auto_launch_game"
    private const val KEY_LAST_VERSION = "stream_server_last_version"
    private const val KEY_SERVER_ENABLED = "stream_server_enabled"
    private const val KEY_JVM_USED = "stream_server_jvm_used"
    private const val KEY_PENDING_LAUNCH = "stream_server_pending_launch"
    private const val KEY_STREAM_AUDIO = "stream_server_audio"

    private val mmkv get() = launcherMMKV()

    /** 监听端口 */
    var port: Int
        get() = mmkv.decodeInt(KEY_PORT, StreamProtocol.DEFAULT_PORT).coerceIn(1024, 65535)
        set(value) {
            mmkv.encode(KEY_PORT, value.coerceIn(1024, 65535))
        }

    /** 访问口令，空串表示不校验 */
    var password: String
        get() = mmkv.decodeString(KEY_PASSWORD, "") ?: ""
        set(value) {
            mmkv.encode(KEY_PASSWORD, value)
        }

    /** 串流分辨率宽度 */
    var width: Int
        get() = makeEven(mmkv.decodeInt(KEY_WIDTH, StreamProtocol.DEFAULT_STREAM_WIDTH)).coerceIn(320, 3840)
        set(value) {
            mmkv.encode(KEY_WIDTH, makeEven(value).coerceIn(320, 3840))
        }

    /** 串流分辨率高度 */
    var height: Int
        get() = makeEven(mmkv.decodeInt(KEY_HEIGHT, StreamProtocol.DEFAULT_STREAM_HEIGHT)).coerceIn(240, 2160)
        set(value) {
            mmkv.encode(KEY_HEIGHT, makeEven(value).coerceIn(240, 2160))
        }

    /** 编码码率（kbps） */
    var bitrateKbps: Int
        get() = mmkv.decodeInt(KEY_BITRATE, 6000).coerceIn(500, 100_000)
        set(value) {
            mmkv.encode(KEY_BITRATE, value.coerceIn(500, 100_000))
        }

    /** 编码帧率 */
    var fps: Int
        get() = mmkv.decodeInt(KEY_FPS, 60).coerceIn(15, 120)
        set(value) {
            mmkv.encode(KEY_FPS, value.coerceIn(15, 120))
        }

    /** 开机自启串流服务 */
    var autoStartOnBoot: Boolean
        get() = mmkv.decodeBool(KEY_AUTO_START, true)
        set(value) {
            mmkv.encode(KEY_AUTO_START, value)
        }

    /** 保活：唤醒锁 */
    var keepAlive: Boolean
        get() = mmkv.decodeBool(KEY_KEEP_ALIVE, true)
        set(value) {
            mmkv.encode(KEY_KEEP_ALIVE, value)
        }

    /** 是否显示桌面图标（通过 activity-alias 的启用状态实现） */
    var showLauncherIcon: Boolean
        get() = mmkv.decodeBool(KEY_SHOW_ICON, false)
        set(value) {
            mmkv.encode(KEY_SHOW_ICON, value)
        }

    /** 服务端启动时自动拉起上次运行的游戏 */
    var autoLaunchGame: Boolean
        get() = mmkv.decodeBool(KEY_AUTO_LAUNCH_GAME, false)
        set(value) {
            mmkv.encode(KEY_AUTO_LAUNCH_GAME, value)
        }

    /** 上一次启动的版本名 */
    var lastVersion: String
        get() = mmkv.decodeString(KEY_LAST_VERSION, "") ?: ""
        set(value) {
            mmkv.encode(KEY_LAST_VERSION, value)
        }

    /** 串流服务是否已被启用（用户手动开启过） */
    var serverEnabled: Boolean
        get() = mmkv.decodeBool(KEY_SERVER_ENABLED, false)
        set(value) {
            mmkv.encode(KEY_SERVER_ENABLED, value)
        }

    /**
     * 本进程里是否已经启动过一次内嵌 JVM。
     * JVM 在同一进程内只能启动一次（JLI_Launch 不可重入），
     * 所以游戏退出后想再启动，必须重启进程。
     */
    var jvmUsed: Boolean
        get() = mmkv.decodeBool(KEY_JVM_USED, false)
        set(value) {
            mmkv.encode(KEY_JVM_USED, value)
        }

    /**
     * 进程重启后需要自动拉起的版本名（空串表示不需要）
     */
    var pendingLaunch: String
        get() = mmkv.decodeString(KEY_PENDING_LAUNCH, "") ?: ""
        set(value) {
            mmkv.encode(KEY_PENDING_LAUNCH, value)
        }

    /** 音频来源（旧布尔开关的迁移目标） */
    private const val KEY_AUDIO_SOURCE = "stream_server_audio_source"

    /**
     * 音频来源。
     *
     * 默认 [AudioPipeline.AudioSource.WAVE_PIPE]：拦截 OpenAL 输出，
     * 宿主设备不出声，也不需要任何权限。这是"服务器"这个定位下最合适的做法。
     */
    fun audioSource(): AudioPipeline.AudioSource {
        val raw = mmkv.decodeString(KEY_AUDIO_SOURCE, null)
        if (raw != null) {
            return runCatching { AudioPipeline.AudioSource.valueOf(raw) }
                .getOrDefault(AudioPipeline.AudioSource.WAVE_PIPE)
        }
        //兼容旧版本：过去只有一个"是否串流音频"的开关
        val legacyEnabled = mmkv.decodeBool(KEY_STREAM_AUDIO, true)
        return if (legacyEnabled) {
            AudioPipeline.AudioSource.WAVE_PIPE
        } else {
            AudioPipeline.AudioSource.OFF
        }
    }

    fun setAudioSource(source: AudioPipeline.AudioSource) {
        mmkv.encode(KEY_AUDIO_SOURCE, source.name)
    }

    /** 音频采集是否真的起来了（只读，运行期状态） */
    @Volatile
    var audioActive: Boolean = false

    private fun makeEven(value: Int): Int = if (value % 2 == 0) value else value - 1

    /**
     * 服务端强制使用百分比分辨率规则并把比例固定为 100%，
     * 这样游戏画面正好铺满屏幕，客户端发来的位置比例才能直接映射
     */
    fun applyResolutionPolicy() {
        AllSettings.resolutionRule.save(ResolutionRule.PERCENTAGE)
        AllSettings.resolutionRatio.save(100)
        //服务端不需要游戏内的悬浮菜单球干扰画面
        AllSettings.showMenuBall.save(false)
    }

    /**
     * 是否已获得悬浮窗权限（安卓 10 以上从后台拉起 Activity 需要该豁免）
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }
}
