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
import android.util.Log
import android.view.Surface
import androidx.compose.ui.unit.IntSize
import com.movtery.streamcore.StreamProtocol
import com.movtery.zalithlauncher.bridge.CURSOR_DISABLED
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.movtery.zalithlauncher.bridge.ZLBridgeStates
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.control.ControlManager
import com.movtery.zalithlauncher.game.launch.GameLauncher
import com.movtery.zalithlauncher.game.launch.LaunchConfig
import com.movtery.zalithlauncher.game.launch.MCOptions
import com.movtery.zalithlauncher.game.path.getGameHome
import com.movtery.zalithlauncher.game.plugin.PluginLoader
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.support.lwjgl3ify.patchLwjgl3ifyIfNeeded
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import com.movtery.zalithlauncher.game.version.installed.VersionInfoParser
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.game.version.mod.AllModReader
import com.movtery.zalithlauncher.game.version.mod.isEnabled
import com.movtery.zalithlauncher.utils.GSON
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.lwjgl.glfw.CallbackBridge
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GameSession"

/**
 * 服务端游戏会话：在没有界面的情况下把 Minecraft 跑起来。
 *
 * 与手机版启动器的区别在于：没有 VMActivity、没有控制布局，
 * 直接把编码器的输入 Surface 交给游戏作为渲染目标，游戏主循环跑在
 * 独立线程上，输入完全来自网络（[RemoteInput]）。
 */
object GameSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SessionState())
    val state = _state.asStateFlow()

    @Volatile
    private var gameLauncher: GameLauncher? = null

    /** 游戏主循环所在线程 */
    @Volatile
    private var gameThread: Thread? = null

    private val launching = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /** 监听游戏侧鼠标抓取状态的任务 */
    private var grabMirrorJob: kotlinx.coroutines.Job? = null

    /**
     * 渲染目标，由串流编码器注入
     */
    @Volatile
    var surfaceProvider: SurfaceProvider? = null

    data class SessionState(
        val state: String = StreamProtocol.STATE_IDLE,
        val message: String = "",
        val currentVersion: String = "",
        val width: Int = StreamProtocol.DEFAULT_STREAM_WIDTH,
        val height: Int = StreamProtocol.DEFAULT_STREAM_HEIGHT,
        val fps: Int = 0,
        val bitrateKbps: Int = 0,
        val grabbed: Boolean = false
    )

    interface SurfaceProvider {
        /** 提供一个可用的渲染 Surface */
        fun provideSurface(width: Int, height: Int): Surface?
    }

    /**
     * 读取已安装的版本名列表
     */
    suspend fun listVersions(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            VersionsManager.refresh("GameSession.listVersions")
            VersionsManager.waitForRefresh()
        }
        VersionsManager.versions.value.filter { it.isValid() }.map { it.getVersionName() }
    }

    /**
     * 启动游戏
     * @param versionName 指定的版本名，null 表示使用列表中的第一个
     * @return 是否成功发起启动
     */
    fun launch(context: Context, versionName: String?): Boolean {
        if (launching.get() || _state.value.state == StreamProtocol.STATE_RUNNING) {
            Log.w(TAG, "Launch ignored: a game is already running or launching.")
            return false
        }
        //内嵌 JVM 在同一进程里只能创建一次：如果这一轮已经跑过游戏，
        //先把要启动的版本记下来，重启进程后自动接着启动。
        if (ServerSettings.jvmUsed) {
            Logger.info(TAG, "JVM already used in this process, restarting to launch again")
            ServerSettings.pendingLaunch = versionName ?: ServerSettings.lastVersion
            ServerSettings.serverEnabled = true
            GameRuntime.restartProcess(context)
            return true
        }
        if (!launching.compareAndSet(false, true)) return false
        stopping.set(false)
        updateState(StreamProtocol.STATE_LAUNCHING, "Preparing game")

        scope.launch {
            try {
                doLaunch(context.applicationContext, versionName)
            } catch (e: Throwable) {
                Logger.error(TAG, "Failed to launch the game", e)
                updateState(StreamProtocol.STATE_ERROR, e.message ?: e.javaClass.simpleName)
                launching.set(false)
            }
        }
        return true
    }

    private suspend fun doLaunch(context: Context, versionName: String?) {
        //服务端通常是被开机广播直接拉起的，没有走过 SplashActivity，
        //必须自己把 JRE/LWJGL 等资源解包出来
        ServerBootstrap.ensureComponents(context)

        VersionsManager.refresh("GameSession.doLaunch", trySetVersion = versionName)
        VersionsManager.waitForRefresh()
        AccountsManager.reloadAccounts()
        repeat(30) {
            if (AccountsManager.currentAccountFlow.value != null) return@repeat
            delay(100)
        }

        val version = withContext(Dispatchers.IO) {
            val all = VersionsManager.versions.value.filter { it.isValid() }
            if (versionName.isNullOrBlank()) {
                all.firstOrNull()
            } else {
                all.firstOrNull { it.getVersionName() == versionName }
                    ?: VersionsManager.loadVersion(getGameHome(), versionName)
            }
        } ?: run {
            updateState(StreamProtocol.STATE_ERROR, "No valid Minecraft version installed")
            launching.set(false)
            return
        }

        //服务端无人值守，使用离线账号最稳当
        val account = AccountsManager.currentAccountFlow.value ?: Account()
        version.offlineAccountLogin = true
        version.launchManifest = prepareManifest(version)

        val width = ServerSettings.width
        val height = ServerSettings.height

        val surface = surfaceProvider?.provideSurface(width, height) ?: run {
            updateState(StreamProtocol.STATE_ERROR, "No encoder surface available")
            launching.set(false)
            return
        }

        ServerSettings.lastVersion = version.getVersionName()
        //标记本进程的 JVM 即将被占用
        ServerSettings.jvmUsed = true
        updateState(
            StreamProtocol.STATE_LAUNCHING,
            "Launching ${version.getVersionName()}",
            version = version.getVersionName(),
            width = width,
            height = height
        )

        //渲染器/插件/MC 选项
        Renderers.init()
        runCatching { PluginLoader.loadAllPlugins(context, false) }
            .onFailure { Logger.warning(TAG, "Plugin loading failed", it) }
        ControlManager.checkDefaultAndRefresh(context)
        MCOptions.setup(context, version)
        MCOptions.apply {
            set("fullscreen", "false")
            set("touchscreen", "false")
            set("overrideWidth", width.toString())
            set("overrideHeight", height.toString())
            //没有前台界面时不要让游戏因为失焦而暂停
            set("pauseOnLostFocus", "false")
            save()
        }

        RemoteInput.updateStreamSize(width, height)
        RemoteInput.syncFromSettings()

        val launcher = GameLauncher(
            context = context,
            config = LaunchConfig(version, account),
            onExit = { code, isSignal ->
                Logger.info(TAG, "Game exited: code=$code, isSignal=$isSignal")
                gameLauncher = null
                gameThread = null
                launching.set(false)
                if (stopping.get()) {
                    updateState(StreamProtocol.STATE_IDLE, "")
                } else {
                    updateState(
                        if (code == 0) StreamProtocol.STATE_STOPPED else StreamProtocol.STATE_ERROR,
                        if (code == 0) "" else "Game exited with code $code"
                    )
                }
                //游戏侧的 System.exit 会被 exit hook 接管并结束整个进程，
                //这里让进程体面地重启，服务端才能继续等待下一次启动指令。
                if (ServerSettings.keepAlive && ServerSettings.serverEnabled) {
                    GameRuntime.restartProcess(context)
                }
            },
            openPath = { /* 服务端没有文件浏览界面 */ }
        )
        gameLauncher = launcher

        //游戏主循环会一直阻塞到游戏退出，必须放在独立线程
        val thread = Thread({
            try {
                //把编码器的 Surface 交给游戏的 GLFW 桥，之后游戏直接渲染到编码器里
                ZLBridge.setupBridgeWindow(surface)
                CallbackBridge.sendUpdateWindowSize(width, height)
                CallbackBridge.nativeSetWindowAttrib(GLFW_FOCUSED, 1)
                CallbackBridge.nativeSetWindowAttrib(GLFW_HOVERED, 1)
                //抓取状态由游戏侧驱动，直接镜像给所有客户端
                grabMirrorJob = scope.launch(Dispatchers.Default) {
                    ZLBridgeStates.cursorMode.collect { cursorMode ->
                        RemoteInput.syncGrabFromGame(cursorMode == CURSOR_DISABLED)
                    }
                }
                CallbackBridge.setGraphicOutputListener {
                    //首帧渲染出来就认为游戏已经就绪
                    if (_state.value.state != StreamProtocol.STATE_RUNNING) {
                        updateState(StreamProtocol.STATE_RUNNING, "")
                    }
                }

                val exitCode = runBlocking {
                    launcher.launch(IntSize(width, height))
                }
                Logger.info(TAG, "Game thread finished, exit code = $exitCode")
            } catch (e: Throwable) {
                Logger.error(TAG, "Game thread crashed", e)
                updateState(StreamProtocol.STATE_ERROR, e.message ?: e.javaClass.simpleName)
                launching.set(false)
            }
        }, "ZalithGameMain")
        thread.isDaemon = false
        gameThread = thread
        thread.start()

        //超时后仍然没有首帧，也给客户端一个明确状态
        scope.launch {
            repeat(180) {
                if (_state.value.state == StreamProtocol.STATE_RUNNING) return@launch
                delay(500)
            }
            if (_state.value.state == StreamProtocol.STATE_LAUNCHING) {
                updateState(StreamProtocol.STATE_RUNNING, "", version = version.getVersionName())
            }
        }
    }

    /**
     * 与 GameLaunchFlow 保持一致：扫描模组、必要时打 lwjgl3ify 补丁
     */
    private suspend fun prepareManifest(version: com.movtery.zalithlauncher.game.version.installed.Version): String? {
        return runCatching {
            val modsDir = VersionFolders.MOD.getDir(version.getGameDir())
            val mods = AllModReader(modsDir).readAllLocals()
            if (mods.any { it.id == "touchcontroller" && it.file.isEnabled() }) {
                version.enableTouchProxy = true
            }
            val patched = patchLwjgl3ifyIfNeeded(version, mods)
            val manifest = patched ?: VersionInfoParser(version).setInheriting().build()
            GSON.toJson(manifest)
        }.onFailure {
            Logger.warning(TAG, "Failed to prepare the launch manifest", it)
        }.getOrNull()
    }

    /**
     * 停止游戏。
     *
     * 内嵌 JVM 的游戏退出会触发 native exit hook，最终回调 [GameLauncher] 的 onExit；
     * 这里只负责发出退出请求并回收状态。
     */
    fun stop(context: Context) {
        val launcher = gameLauncher
        if (launcher == null) {
            updateState(StreamProtocol.STATE_IDLE, "")
            launching.set(false)
            return
        }
        stopping.set(true)
        //停止后不需要自动续跑
        ServerSettings.pendingLaunch = ""
        updateState(StreamProtocol.STATE_IDLE, "Stopping")
        scope.launch {
            runCatching { launcher.exit() }.onFailure {
                Logger.error(TAG, "Failed to stop the game", it)
            }
            delay(1200)
            gameLauncher = null
            gameThread = null
            launching.set(false)
            updateState(StreamProtocol.STATE_IDLE, "")
            //游戏 JVM 与宿主进程共存亡，停游戏等于结束进程；
            //重启进程让服务端回到"等待下一次指令"的干净状态。
            GameRuntime.restartProcess(context)
        }
    }

    fun updateState(
        state: String,
        message: String,
        version: String = _state.value.currentVersion,
        width: Int = _state.value.width,
        height: Int = _state.value.height,
        fps: Int = _state.value.fps,
        bitrateKbps: Int = _state.value.bitrateKbps,
        grabbed: Boolean = _state.value.grabbed
    ) {
        _state.value = SessionState(
            state = state,
            message = message,
            currentVersion = version,
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = bitrateKbps,
            grabbed = grabbed
        )
    }

    fun updateStreamStats(fps: Int, bitrateKbps: Int) {
        val current = _state.value
        if (current.fps == fps && current.bitrateKbps == bitrateKbps) return
        _state.value = current.copy(fps = fps, bitrateKbps = bitrateKbps)
    }

    fun updateGrabbed(grabbed: Boolean) {
        val current = _state.value
        if (current.grabbed == grabbed) return
        _state.value = current.copy(grabbed = grabbed)
    }

    fun isRunning(): Boolean = gameLauncher != null

    /**
     * 服务被销毁时的收尾。
     *
     * 注意：游戏 JVM 与宿主进程共存亡，服务被销毁不代表要停止游戏，
     * 因此这里只取消监听，不触发退出。
     */
    fun shutdown() {
        grabMirrorJob?.cancel()
        grabMirrorJob = null
    }

    /** GLFW 窗口属性常量（与 LwjglGlfwKeycode 一致，避免模块依赖） */
    private const val GLFW_FOCUSED = 0x20001
    private const val GLFW_HOVERED = 0x2000B

}
