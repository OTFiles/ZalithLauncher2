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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.movtery.streamcore.StreamProtocol
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.setting.loadAllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "ServerSettingsUI"

/** 供其它应用/脚本打开设置界面用的 action */
const val ACTION_SETTINGS = "com.movtery.zalithlauncher.server.action.SETTINGS"

/**
 * 服务端设置界面。
 *
 * 服务端没有主界面（默认也没有桌面图标），所有操作都集中在这里：
 *  - 查看串流地址、客户端数量、游戏状态
 *  - 调整端口/口令/分辨率/码率
 *  - 开关开机自启、保活、桌面图标
 *  - 选择并启动/停止 Minecraft
 *  - 申请必要权限
 *
 * 由于没有桌面图标，其它应用可以用下面的方式打开本界面：
 * ```
 * adb shell am start -n com.movtery.zalithlauncher.server/com.movtery.zalithlauncher.server.ServerSettingsActivity
 * # 或
 * adb shell am start -a com.movtery.zalithlauncher.server.action.SETTINGS
 * ```
 */
class ServerSettingsActivity : ComponentActivity() {
    private val requestAudioCapture = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        if (result.resultCode == RESULT_OK && data != null && manager != null) {
            runCatching {
                val projection = manager.getMediaProjection(result.resultCode, data)
                ServerStreamEngine.instance.mediaProjection = projection
                projection.registerCallback(
                    object : android.media.projection.MediaProjection.Callback() {
                        override fun onStop() {
                            ServerStreamEngine.instance.mediaProjection = null
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
                //授权后重启串流，让音频采集生效
                ServerService.refresh(this)
            }.onFailure {
                Logger.error(TAG, "Failed to obtain media projection", it)
            }
        }
    }

    /** 申请屏幕/音频录制授权（音频串流需要） */
    fun requestMediaProjection() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            ?: return
        runCatching {
            requestAudioCapture.launch(manager.createScreenCaptureIntent())
        }.onFailure {
            Logger.error(TAG, "Cannot launch the capture intent", it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlobalContext = applicationContext
        runCatching {
            loadAllSettings(this)
            ServerBootstrap.initApp(this)
        }
        //服务端没有图标时用户只能从别处打开，这里保证一旦打开就顺带把服务拉起来
        if (ServerSettings.serverEnabled) {
            runCatching { ServerService.start(this) }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ServerSettingsScreen(this@ServerSettingsActivity)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSettingsScreen(activity: ServerSettingsActivity) {
    var port by remember { mutableStateOf(ServerSettings.port.toString()) }
    var password by remember { mutableStateOf(ServerSettings.password) }
    var width by remember { mutableStateOf(ServerSettings.width.toString()) }
    var height by remember { mutableStateOf(ServerSettings.height.toString()) }
    var bitrate by remember { mutableStateOf(ServerSettings.bitrateKbps.toString()) }
    var fps by remember { mutableStateOf(ServerSettings.fps.toString()) }

    var autoStart by remember { mutableStateOf(ServerSettings.autoStartOnBoot) }
    var keepAlive by remember { mutableStateOf(ServerSettings.keepAlive) }
    var showIcon by remember { mutableStateOf(ServerSettings.showLauncherIcon) }
    var autoLaunch by remember { mutableStateOf(ServerSettings.autoLaunchGame) }
    var streamAudio by remember { mutableStateOf(ServerSettings.streamAudio) }

    var statusText by remember { mutableStateOf("") }
    var addresses by remember { mutableStateOf(listOf<String>()) }
    var serverRunning by remember { mutableStateOf(ServerStreamEngine.instance.isRunning()) }
    var clientCount by remember { mutableStateOf(0) }
    var gameRunning by remember { mutableStateOf(GameSession.isRunning()) }
    var versions = remember { mutableStateListOf<String>() }
    var selectedVersion by remember { mutableStateOf(ServerSettings.lastVersion) }

    fun refreshStatus() {
        val engine = ServerStreamEngine.instance
        serverRunning = engine.isRunning()
        clientCount = engine.clientCount()
        gameRunning = GameSession.isRunning()
        addresses = engine.localAddresses()
        val state = GameSession.state.value
        statusText = buildString {
            append("Stream: ")
            append(if (serverRunning) "listening" else "stopped")
            append("  |  Game: ")
            append(state.state)
            if (state.message.isNotEmpty()) {
                append(" (")
                append(state.message)
                append(")")
            }
            if (state.currentVersion.isNotEmpty()) {
                append("  |  ")
                append(state.currentVersion)
            }
            append("  |  ")
            append("${state.fps}fps ")
            append("${state.bitrateKbps}kbps")
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
        versions.clear()
        versions.addAll(GameSession.listVersions())
        if (selectedVersion.isEmpty()) selectedVersion = versions.firstOrNull() ?: ""
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshStatus()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ZL2 Stream Server") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Clients connected: $clientCount",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Address:", style = MaterialTheme.typography.bodySmall)
                    if (addresses.isEmpty()) {
                        Text("  (no network)", style = MaterialTheme.typography.bodySmall)
                    } else {
                        addresses.forEach { addr ->
                            Text(
                                "  $addr:${ServerSettings.port}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            ServerSettings.serverEnabled = true
                            ServerService.start(activity)
                            refreshStatus()
                        }) { Text("Start") }
                        OutlinedButton(onClick = {
                            ServerSettings.serverEnabled = false
                            ServerService.stop(activity)
                            refreshStatus()
                        }) { Text("Stop") }
                        OutlinedButton(onClick = {
                            ServerService.refresh(activity)
                            refreshStatus()
                        }) { Text("Restart") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stream", style = MaterialTheme.typography.titleMedium)
                    NumberField("Port", port) { port = it }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (empty = no auth)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Width", width, Modifier.weight(1f)) { width = it }
                        NumberField("Height", height, Modifier.weight(1f)) { height = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Bitrate kbps", bitrate, Modifier.weight(1f)) { bitrate = it }
                        NumberField("FPS", fps, Modifier.weight(1f)) { fps = it }
                    }
                    Button(onClick = {
                        ServerSettings.port = port.toIntOrNull() ?: ServerSettings.port
                        ServerSettings.password = password
                        ServerSettings.width = width.toIntOrNull() ?: ServerSettings.width
                        ServerSettings.height = height.toIntOrNull() ?: ServerSettings.height
                        ServerSettings.bitrateKbps = bitrate.toIntOrNull() ?: ServerSettings.bitrateKbps
                        ServerSettings.fps = fps.toIntOrNull() ?: ServerSettings.fps
                        //重开服务让新参数生效
                        ServerService.refresh(activity)
                        refreshStatus()
                    }) { Text("Apply & restart stream") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Game", style = MaterialTheme.typography.titleMedium)
                    if (versions.isEmpty()) {
                        Text(
                            "No Minecraft version found. Put an installed version into\n" +
                                    "Android/data/com.movtery.zalithlauncher.server/files/.minecraft/versions",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("Versions:", style = MaterialTheme.typography.bodySmall)
                        versions.forEach { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = selectedVersion == name,
                                    onCheckedChange = { selectedVersion = name }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                GameSession.launch(activity, selectedVersion.ifEmpty { null })
                                refreshStatus()
                            },
                            enabled = !gameRunning
                        ) { Text("Launch game") }
                        OutlinedButton(
                            onClick = {
                                GameSession.stop(activity)
                                refreshStatus()
                            },
                            enabled = gameRunning
                        ) { Text("Stop game") }
                        OutlinedButton(onClick = {
                            activity.lifecycleScopeLaunch {
                                versions.clear()
                                versions.addAll(GameSession.listVersions())
                                if (selectedVersion.isEmpty()) selectedVersion = versions.firstOrNull() ?: ""
                            }
                        }) { Text("Refresh") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Behaviour", style = MaterialTheme.typography.titleMedium)
                    SwitchRow("Start on boot", autoStart) {
                        autoStart = it
                        ServerSettings.autoStartOnBoot = it
                    }
                    SwitchRow("Keep alive (wake lock + sticky service)", keepAlive) {
                        keepAlive = it
                        ServerSettings.keepAlive = it
                    }
                    SwitchRow("Launch last game when the service starts", autoLaunch) {
                        autoLaunch = it
                        ServerSettings.autoLaunchGame = it
                    }
                    SwitchRow("Stream game audio (needs capture permission)", streamAudio) {
                        streamAudio = it
                        ServerSettings.streamAudio = it
                        if (it) activity.requestMediaProjection()
                        ServerService.refresh(activity)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SwitchRow("Show launcher icon", showIcon) {
                        showIcon = it
                        ServerSettings.showLauncherIcon = it
                        setLauncherIconVisible(activity, it)
                    }
                    Text(
                        "The icon is hidden by default. You can also open this screen with:\n" +
                                "am start -a ${ACTION_SETTINGS}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { activity.openAppSettings() }) {
                        Text("App settings")
                    }
                    OutlinedButton(onClick = { activity.requestMediaProjection() }) {
                        Text("Capture permission (audio)")
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            activity.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
                            )
                        }
                    }) { Text("All files access") }
                    OutlinedButton(onClick = {
                        val pm = activity.getSystemService(PowerManager::class.java)
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                            runCatching {
                                activity.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(android.net.Uri.parse("package:${activity.packageName}"))
                                )
                            }
                        }
                    }) { Text("Ignore battery optimization") }
                    OutlinedButton(onClick = {
                        runCatching {
                            activity.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
                            )
                        }
                    }) { Text("Allow background activity start (overlay)") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun ComponentActivity.lifecycleScopeLaunch(block: suspend () -> Unit) {
    lifecycleScope.launch(Dispatchers.IO) { block() }
}

private fun ComponentActivity.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            if (text.isEmpty() || text.all { it.isDigit() }) onValueChange(text)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 通过启用/禁用 activity-alias 来控制桌面图标
 */
internal fun setLauncherIconVisible(context: Context, visible: Boolean) {
    val alias = ComponentName(context.packageName, "${context.packageName}.server.ServerIconAlias")
    runCatching {
        context.packageManager.setComponentEnabledSetting(
            alias,
            if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }.onFailure {
        Logger.error(TAG, "Failed to toggle the launcher icon", it)
    }
}
