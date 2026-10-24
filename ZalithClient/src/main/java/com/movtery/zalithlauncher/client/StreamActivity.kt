/*
 * Zalith Launcher 2 - Client
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

package com.movtery.zalithlauncher.client

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.movtery.streamcore.InputCodec
import com.movtery.streamcore.Keycodes
import com.movtery.streamcore.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 串流播放界面。
 *
 * 视频：SurfaceView + MediaCodec 硬解，不经 CPU。
 * 输入：整个 SurfaceView 上的手势都会被翻译成串流输入，
 *       顶部叠加一层很薄的浮层用来显示状态、切换触摸模式、发送文本。
 */
class StreamActivity : ComponentActivity(), StreamConnection.Listener {

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PASSWORD = "extra_password"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connection: StreamConnection? = null

    private var surfaceView: SurfaceView? = null
    private var decoder: VideoDecoder? = null
    private var controller: InputController? = null

    /** 当前视频尺寸，用于把触摸坐标换算成比例 */
    private var videoWidth = 1280
    private var videoHeight = 720
    private val viewSize = IntArray(2)

    private var stateText by mutableStateOf("连接中…")
    private var statsText by mutableStateOf("")
    private var connected by mutableStateOf(false)
    private var mode by mutableStateOf(InputController.TouchMode.WALK)
    private var showVirtualKeys by mutableStateOf(true)
    private var gameState by mutableStateOf(StreamProtocol.STATE_IDLE)
    private var messageText by mutableStateOf("")

    private var host = ""
    private var port = StreamProtocol.DEFAULT_PORT
    private var password = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, StreamProtocol.DEFAULT_PORT)
        password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        mode = InputController.TouchMode.entries[
            (ClientPrefs.touchMode(this)).coerceIn(0, InputController.TouchMode.entries.size - 1)
        ]
        showVirtualKeys = ClientPrefs.showVirtualKeys(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    StreamSurface(
                        onSurfaceReady = { surface ->
                            startStreaming(surface)
                        },
                        onSurfaceDestroyed = {
                            stopStreaming()
                        },
                        onTouch = ::handleTouch
                    )

                    TopBarHud(
                        state = stateText,
                        stats = statsText,
                        mode = mode,
                        showVirtualKeys = showVirtualKeys,
                        connected = connected,
                        onDisconnect = {
                            connection?.close("Closed by user")
                            finish()
                        },
                        onSwitchMode = { newMode ->
                            mode = newMode
                            controller?.mode = newMode
                            ClientPrefs.setTouchMode(this@StreamActivity, newMode.ordinal)
                        },
                        onToggleVirtualKeys = {
                            showVirtualKeys = !showVirtualKeys
                            ClientPrefs.setShowVirtualKeys(this@StreamActivity, showVirtualKeys)
                        },
                        onSendText = { text -> controller?.sendText(text) },
                        onKey = { key -> controller?.tapKey(key) }
                    )

                    if (showVirtualKeys) {
                        VirtualKeyLayer(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onKeyDown = { key -> controller?.setKey(key, true) },
                            onKeyUp = { key -> controller?.setKey(key, false) }
                        )
                    }

                    //启动/停止游戏（服务端远程控制）
                    RemoteControlLayer(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                        connected = connected,
                        gameState = gameState,
                        message = messageText,
                        onLaunch = {
                            connection?.sendCommand(
                                StreamProtocol.Command(cmd = StreamProtocol.CMD_LAUNCH)
                            )
                        },
                        onStopGame = {
                            connection?.sendCommand(
                                StreamProtocol.Command(cmd = StreamProtocol.CMD_STOP)
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        scope.cancel()
    }

    // region 生命周期与连接

    private fun startStreaming(surface: Surface) {
        decoder?.stop()
        val dec = VideoDecoder(surface)
        decoder = dec

        val conn = StreamConnection(host, port, password, this)
        connection = conn
        controller = InputController(conn).also {
            it.mode = mode
            it.videoWidth = videoWidth
            it.videoHeight = videoHeight
        }

        stateText = getString(R.string.client_connecting)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { conn.connect() }
            }
            result.onSuccess { hello ->
                connected = true
                stateText = getString(R.string.client_connected)
                videoWidth = hello.width
                videoHeight = hello.height
                decoder?.start(hello.width, hello.height)
                //请求当前状态
                conn.sendCommand(StreamProtocol.Command(cmd = StreamProtocol.CMD_STATUS))
            }.onFailure { e ->
                connected = false
                stateText = "${getString(R.string.client_error)}: ${e.message}"
            }
        }
    }

    private fun stopStreaming() {
        decoder?.stop()
        decoder = null
        connection?.close("Activity closed")
        connection = null
        controller = null
        connected = false
    }
    // endregion

    // region 连接回调

    override fun onConnected(hello: StreamProtocol.ServerHello) {
        runOnUiThread {
            connected = true
            stateText = getString(R.string.client_connected)
        }
    }

    override fun onVideo(data: ByteArray) {
        decoder?.submit(data)
    }

    override fun onMeta(meta: StreamProtocol.Meta) {
        runOnUiThread {
            gameState = meta.state
            messageText = meta.message
            videoWidth = meta.width.takeIf { it > 0 } ?: videoWidth
            videoHeight = meta.height.takeIf { it > 0 } ?: videoHeight
            val flick = when (meta.state) {
                StreamProtocol.STATE_LAUNCHING -> getString(R.string.client_state_launching)
                StreamProtocol.STATE_RUNNING -> getString(R.string.client_state_running)
                StreamProtocol.STATE_STOPPED -> getString(R.string.client_state_stopped)
                StreamProtocol.STATE_ERROR -> getString(R.string.client_state_error)
                else -> getString(R.string.client_state_idle)
            }
            stateText = if (meta.message.isEmpty()) flick else "$flick · ${meta.message}"
            statsText = getString(R.string.client_stats, meta.fps, meta.bitrateKbps)
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            connected = false
            stateText = "${getString(R.string.client_error)}: $reason"
            controller?.releaseAllKeys()
        }
    }
    // endregion

    // region 输入

    private fun handleTouch(event: MotionEvent, view: View) {
        val ctrl = controller ?: return
        view.getLocationOnScreen(viewSize)
        //坐标转换为 SurfaceView 局部坐标
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                ctrl.onTouchDown(event.getPointerId(index), event.getX(index), event.getY(index), w, h)
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    ctrl.onTouchMove(
                        event.getPointerId(index),
                        event.getX(index),
                        event.getY(index),
                        w,
                        h
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                ctrl.onTouchUp(event.getPointerId(index), event.getX(index), event.getY(index), w, h)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    //单击判定：短按且没有移动
                    val duration = event.eventTime - event.downTime
                    if (duration < 220) {
                        ctrl.onTap(event.x, event.y, w, h, rightClick = false)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                ctrl.releaseAllKeys()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ctrl = controller
        if (ctrl != null && connected) {
            //音量键保留给系统
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_BACK
            ) {
                return super.dispatchKeyEvent(event)
            }
            if (event.isFromSource(InputDevice.SOURCE_KEYBOARD) ||
                event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.device == null
            ) {
                if (ctrl.onKeyEvent(event)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        //返回键先退出抓取模式，再退出界面
        val ctrl = controller
        if (ctrl != null && ctrl.grabbed) {
            ctrl.setGrabbed(false)
            return
        }
        super.onBackPressed()
    }
    // endregion

    // region UI

    @Composable
    private fun StreamSurface(
        onSurfaceReady: (Surface) -> Unit,
        onSurfaceDestroyed: () -> Unit,
        onTouch: (MotionEvent, View) -> Unit
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                object : SurfaceView(context) {
                    init {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        })
                    }

                    @SuppressLint("ClickableViewAccessibility")
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        onTouch(event, this)
                        return true
                    }

                    override fun performClick(): Boolean {
                        super.performClick()
                        return true
                    }
                }.also { surfaceView = it }
            }
        )
    }

    @Composable
    private fun TopBarHud(
        state: String,
        stats: String,
        mode: InputController.TouchMode,
        showVirtualKeys: Boolean,
        connected: Boolean,
        onDisconnect: () -> Unit,
        onSwitchMode: (InputController.TouchMode) -> Unit,
        onToggleVirtualKeys: () -> Unit,
        onSendText: (String) -> Unit,
        onKey: (Int) -> Unit
    ) {
        var modeMenu by remember { mutableStateOf(false) }
        var textDialog by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }

        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .fillMaxWidth(0.7f),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.45f)
                )
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state, style = MaterialTheme.typography.bodySmall, color = Color.White)
                    if (stats.isNotEmpty()) {
                        Text(stats, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box {
                    FilledTonalButton(onClick = { modeMenu = true }) {
                        Text(
                            when (mode) {
                                InputController.TouchMode.WALK -> "摇杆"
                                InputController.TouchMode.CAPTURE -> "视角"
                                InputController.TouchMode.DIRECT -> "指针"
                            }
                        )
                    }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("摇杆 + 视角（推荐）") },
                            onClick = { modeMenu = false; onSwitchMode(InputController.TouchMode.WALK) }
                        )
                        DropdownMenuItem(
                            text = { Text("全屏转视角") },
                            onClick = { modeMenu = false; onSwitchMode(InputController.TouchMode.CAPTURE) }
                        )
                        DropdownMenuItem(
                            text = { Text("指针模式") },
                            onClick = { modeMenu = false; onSwitchMode(InputController.TouchMode.DIRECT) }
                        )
                    }
                }
                FilledTonalButton(onClick = onToggleVirtualKeys) {
                    Text(if (showVirtualKeys) "隐藏按键" else "显示按键")
                }
                FilledTonalButton(onClick = { textDialog = !textDialog }) {
                    Text("文本")
                }
                OutlinedButton(onClick = onDisconnect) { Text("断开") }
            }

            if (textDialog) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xEE202024))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text("发送到游戏") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                onSendText(text)
                                text = ""
                                textDialog = false
                            }) { Text("发送") }
                            OutlinedButton(onClick = { textDialog = false }) { Text("取消") }
                            OutlinedButton(onClick = { onKey(Keycodes.KEY_ENTER) }) { Text("回车") }
                        }
                    }
                }
            }
        }
    }

    /**
     * 底部虚拟按键：WASD / 跳跃 / 潜行 / 背包 / 丢弃
     */
    @Composable
    private fun VirtualKeyLayer(
        modifier: Modifier = Modifier,
        onKeyDown: (Int) -> Unit,
        onKeyUp: (Int) -> Unit
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            VirtualKey("跳", Keycodes.KEY_SPACE, onKeyDown, onKeyUp)
            VirtualKey("潜", Keycodes.KEY_LEFT_SHIFT, onKeyDown, onKeyUp)
            VirtualKey("E", Keycodes.KEY_E, onKeyDown, onKeyUp)
            VirtualKey("Q", Keycodes.KEY_Q, onKeyDown, onKeyUp)
            VirtualKey("1", Keycodes.KEY_1, onKeyDown, onKeyUp)
            VirtualKey("2", Keycodes.KEY_2, onKeyDown, onKeyUp)
            VirtualKey("3", Keycodes.KEY_3, onKeyDown, onKeyUp)
            VirtualKey("ESC", Keycodes.KEY_ESCAPE, onKeyDown, onKeyUp)
        }
    }

    @Composable
    private fun VirtualKey(
        label: String,
        key: Int,
        onKeyDown: (Int) -> Unit,
        onKeyUp: (Int) -> Unit
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                .pressable { pressed ->
                    if (pressed) onKeyDown(key) else onKeyUp(key)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = Color.White)
        }
    }

    @Composable
    private fun RemoteControlLayer(
        modifier: Modifier = Modifier,
        connected: Boolean,
        gameState: String,
        message: String,
        onLaunch: () -> Unit,
        onStopGame: () -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { expanded = !expanded }) { Text("☰") }
            if (expanded) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xEE202024))) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("服务端", color = Color.White)
                        Text("状态: $gameState", color = Color.LightGray)
                        if (message.isNotEmpty()) Text(message, color = Color.LightGray)
                        Button(onClick = onLaunch, enabled = connected) { Text("启动游戏") }
                        OutlinedButton(onClick = onStopGame, enabled = connected) { Text("停止游戏") }
                    }
                }
            }
        }
    }
    // endregion
}

/**
 * 按下/抬起回调的最小 Modifier 封装
 */
private fun Modifier.pressable(onPressChange: (Boolean) -> Unit): Modifier {
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onPressChange(true)
            var released = false
            while (!released) {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id }
                if (pointer != null && !pointer.pressed) {
                    onPressChange(false)
                    released = true
                }
            }
        }
    }
}
