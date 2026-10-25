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

import android.util.Log
import com.google.gson.Gson
import com.movtery.streamcore.InputCodec
import com.movtery.streamcore.StreamProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "StreamServer"
private val gson = Gson()

/**
 * 串流服务端。
 *
 * 负责：
 *  - 监听 TCP 端口，鉴权
 *  - 编码 H.264 画面并分发给所有已连接的客户端
 *  - 接收客户端输入交给 [RemoteInput]
 *  - 接收远程指令（启动/停止游戏、查询版本列表）交给 [GameSession]
 *
 * 只允许一个编码器实例，多客户端时共享同一路码流。
 */
class ServerStreamEngine private constructor() {
    companion object {
        val instance: ServerStreamEngine by lazy { ServerStreamEngine() }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private var encoder: VideoEncoder? = null
    private var audioCapture: GameAudioCapture? = null

    /** 由设置界面申请到的录制授权，音频串流的关键 */
    @Volatile
    var mediaProjection: android.media.projection.MediaProjection? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    /** 串流尺寸与编码参数 */
    @Volatile
    private var streamWidth: Int = ServerSettings.width
    @Volatile
    private var streamHeight: Int = ServerSettings.height
    @Volatile
    private var streamFps: Int = ServerSettings.fps
    @Volatile
    private var streamBitrate: Int = ServerSettings.bitrateKbps

    private var progressThread: Thread? = null

    /**
     * 回调：状态变化时通知前台服务刷新通知
     */
    var onStatusChanged: ((ListeningStatus) -> Unit)? = null

    data class ListeningStatus(
        val running: Boolean,
        val port: Int = 0,
        val clientCount: Int = 0,
        val error: String = ""
    )

    fun isRunning(): Boolean = running.get()

    fun clientCount(): Int = clients.size

    /**
     * 启动监听与编码器
     */
    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        streamWidth = ServerSettings.width
        streamHeight = ServerSettings.height
        streamFps = ServerSettings.fps
        streamBitrate = ServerSettings.bitrateKbps

        //1. 先起编码器，因为游戏需要一个 Surface 才能启动
        val enc = VideoEncoder(
            width = streamWidth,
            height = streamHeight,
            fps = streamFps,
            bitrateKbps = streamBitrate,
            onAccessUnit = ::broadcastVideo,
            onError = { Log.e(TAG, "Encoder error", it) }
        )
        val surface = enc.start()
        if (surface == null) {
            Log.e(TAG, "Encoder unavailable, abort streaming")
            notifyStatus(error = "H.264 encoder unavailable")
            return false
        }
        encoder = enc
        GameSession.surfaceProvider = object : GameSession.SurfaceProvider {
            override fun provideSurface(width: Int, height: Int): android.view.Surface? {
                val current = encoder?.inputSurface
                if (current == null || !current.isValid) {
                    Log.w(TAG, "Requested surface is no longer valid")
                    return null
                }
                return current
            }
        }
        RemoteInput.updateStreamSize(streamWidth, streamHeight)
        startAudioIfPossible()

        //2. 再监听端口
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(ServerSettings.port))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind port ${ServerSettings.port}", e)
            enc.stop()
            encoder = null
            notifyStatus(error = e.message ?: "bind failed")
            return false
        }
        serverSocket = socket
        running.set(true)
        notifyStatus()

        acceptThread = Thread({ acceptLoop(socket) }, "ZalithStreamAccept").apply {
            isDaemon = true
            start()
        }
        progressThread = Thread({ progressLoop() }, "ZalithStreamStats").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        clients.forEach { it.close("Server stopped") }
        clients.clear()
        progressThread?.interrupt()
        progressThread = null
        runCatching { audioCapture?.stop() }
        audioCapture = null
        encoder?.stop()
        encoder = null
        GameSession.surfaceProvider = null
        notifyStatus()
    }

    // region 编码器

    /**
     * 有授权就启动音频采集；没有就静默跳过（画面照常串流）
     */
    private fun startAudioIfPossible() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        if (!ServerSettings.streamAudio) return
        val projection = mediaProjection
        if (projection == null) {
            Log.i(TAG, "No media projection token, audio streaming disabled")
            return
        }
        runCatching {
            val capture = GameAudioCapture(projection) { frame ->
                broadcastAudio(frame)
            }
            if (capture.start()) {
                audioCapture = capture
                ServerSettings.audioActive = true
            }
        }.onFailure {
            Log.w(TAG, "Audio capture unavailable", it)
        }
    }

    private fun broadcastAudio(frame: ByteArray) {
        if (clients.isEmpty()) return
        clients.forEach { it.sendAudio(frame) }
    }

    fun requestKeyFrame() {
        encoder?.forceKeyFrame()
        broadcastParameterSets()
    }

    private fun broadcastVideo(accessUnit: ByteArray) {
        if (clients.isEmpty()) return
        clients.forEach { client ->
            client.sendVideo(accessUnit)
        }
    }

    private fun broadcastParameterSets() {
        val sets = encoder?.parameterSets() ?: return
        clients.forEach { it.queueParameterSets(sets) }
    }

    /**
     * 立即向所有客户端推送一次状态（抓取模式变化等场景）
     */
    fun broadcastMetaNow() {
        runCatching { broadcastMeta() }
    }

    private fun broadcastMeta() {
        val state = GameSession.state.value
        val meta = StreamProtocol.Meta(
            state = state.state,
            message = state.message,
            width = streamWidth,
            height = streamHeight,
            fps = state.fps,
            bitrateKbps = state.bitrateKbps,
            grabbed = RemoteInput.grabbed,
            currentVersion = state.currentVersion,
            audio = audioCapture != null
        )
        val payload = gson.toJson(meta)
        clients.forEach { it.sendFrame(StreamProtocol.TYPE_META, payload) }
    }

    private fun progressLoop() {
        var lastFrameCount = 0
        while (running.get()) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
            val current = encoder?.frameProduced?.get() ?: 0
            val fps = (current - lastFrameCount).coerceAtLeast(0)
            lastFrameCount = current
            GameSession.updateStreamStats(fps, streamBitrate)
            broadcastMeta()
        }
    }
    // endregion

    // region 监听

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "Accept failed", e)
                return
            }
            Thread({ handleClient(client) }, "ZalithStreamClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)

        val hello = try {
            val frame = StreamProtocol.readFrame(input) ?: return
            if (frame.type != StreamProtocol.TYPE_HELLO) return
            gson.fromJson(frame.asString(), StreamProtocol.Handshake::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Handshake read failed", e)
            runCatching { socket.close() }
            return
        } ?: return

        val expected = ServerSettings.password
        val accepted = hello.version == StreamProtocol.PROTOCOL_VERSION &&
                (expected.isEmpty() || expected == hello.password)

        val welcome = StreamProtocol.ServerHello(
            version = StreamProtocol.PROTOCOL_VERSION,
            ok = accepted,
            reason = when {
                hello.version != StreamProtocol.PROTOCOL_VERSION -> "Protocol mismatch, server=${StreamProtocol.PROTOCOL_VERSION}, client=${hello.version}"
                else -> "Wrong password"
            },
            serverName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            width = streamWidth,
            height = streamHeight
        )
        runCatching {
            StreamProtocol.writeFrame(output, StreamProtocol.TYPE_WELCOME, gson.toJson(welcome))
        }.onFailure { return }

        if (!accepted) {
            Log.w(TAG, "Rejected client: ${welcome.reason}")
            runCatching { socket.close() }
            return
        }

        socket.soTimeout = 0
        val connection = ClientConnection(socket, input, output)
        clients.add(connection)
        notifyStatus()

        //新客户端接入：补发参数集 + 请求关键帧，并从下一帧开始能解码
        connection.queueParameterSets(encoder?.parameterSets())
        encoder?.forceKeyFrame()
        connection.sendMeta(gson.toJson(currentMeta()))

        Log.i(TAG, "Client connected: ${socket.inetAddress?.hostAddress}, total=${clients.size}")

        try {
            connection.readLoop()
        } catch (e: IOException) {
            if (running.get()) Log.i(TAG, "Client disconnected: ${e.message}")
        } finally {
            clients.remove(connection)
            connection.close("")
            RemoteInput.releaseAllButtons()
            notifyStatus()
            Log.i(TAG, "Client removed, total=${clients.size}")
        }
    }

    private fun currentMeta(): StreamProtocol.Meta {
        val state = GameSession.state.value
        return StreamProtocol.Meta(
            state = state.state,
            message = state.message,
            width = streamWidth,
            height = streamHeight,
            fps = state.fps,
            bitrateKbps = state.bitrateKbps,
            grabbed = RemoteInput.grabbed,
            currentVersion = state.currentVersion,
            audio = audioCapture != null
        )
    }
    // endregion

    private fun notifyStatus(error: String = "") {
        onStatusChanged?.invoke(
            ListeningStatus(
                running = running.get(),
                port = if (running.get()) ServerSettings.port else 0,
                clientCount = clients.size,
                error = error
            )
        )
    }

    /**
     * 本机可用的 IPv4 地址，设置界面里直接展示给用户
     */
    fun localAddresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { nif ->
                if (!nif.isUp || nif.isLoopback) return@flatMap emptyList()
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // region 单客户端连接

    private inner class ClientConnection(
        private val socket: Socket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream
    ) {
        private val closed = AtomicBoolean(false)
        private val pendingKeyFrames = AtomicInteger(0)

        @Volatile
        private var videoPaused = false

        fun sendFrame(type: Int, payload: String) {
            if (closed.get()) return
            runCatching {
                StreamProtocol.writeFrame(output, type, payload)
            }.onFailure {
                close("write failed")
            }
        }

        fun sendAudio(frame: ByteArray) {
            if (closed.get()) return
            runCatching {
                StreamProtocol.writeFrame(output, StreamProtocol.TYPE_AUDIO, frame)
            }.onFailure {
                close("write failed")
            }
        }

        fun sendVideo(accessUnit: ByteArray) {
            if (closed.get() || videoPaused) return
            runCatching {
                StreamProtocol.writeFrame(output, StreamProtocol.TYPE_VIDEO, accessUnit)
            }.onFailure {
                close("write failed")
            }
        }

        fun queueParameterSets(sets: ByteArray?) {
            if (sets == null || closed.get()) return
            runCatching {
                StreamProtocol.writeFrame(output, StreamProtocol.TYPE_VIDEO, sets)
            }.onFailure {
                close("write failed")
            }
        }

        fun sendMeta(json: String) {
            sendFrame(StreamProtocol.TYPE_META, json)
        }

        fun readLoop() {
            val decoder = InputCodec.Decoder(RemoteInput)
            var lastPing = System.currentTimeMillis()
            while (!closed.get() && running.get()) {
                val frame = StreamProtocol.readFrame(input) ?: return
                when (frame.type) {
                    StreamProtocol.TYPE_INPUT -> decoder.decode(frame.payload)
                    StreamProtocol.TYPE_PING -> sendFrame(StreamProtocol.TYPE_PONG, "")
                    StreamProtocol.TYPE_KEYFRAME_REQUEST -> {
                        encoder?.forceKeyFrame()
                        if (pendingKeyFrames.get() > 0) pendingKeyFrames.decrementAndGet()
                    }
                    StreamProtocol.TYPE_COMMAND -> handleCommand(frame.asString())
                    StreamProtocol.TYPE_BYE -> return
                    else -> Unit
                }
                val now = System.currentTimeMillis()
                if (now - lastPing > 5000) {
                    lastPing = now
                    sendFrame(StreamProtocol.TYPE_PING, "")
                    pendingKeyFrames.set(0)
                }
            }
        }

        private fun handleCommand(json: String) {
            val command = runCatching {
                gson.fromJson(json, StreamProtocol.Command::class.java)
            }.getOrNull() ?: return
            when (command.cmd) {
                StreamProtocol.CMD_STATUS -> replyMeta()
                StreamProtocol.CMD_LIST_VERSIONS -> {
                    Thread({
                        val versions = kotlinx.coroutines.runBlocking { GameSession.listVersions() }
                        val meta = currentMeta().copy(versions = versions)
                        sendMeta(gson.toJson(meta))
                    }, "ZalithListVersions").start()
                }
                StreamProtocol.CMD_LAUNCH -> {
                    //服务端上下文永远存在：Service 或 Application 都行
                    GameSession.launch(
                        ServerService.instance ?: com.movtery.zalithlauncher.context.GlobalContext,
                        command.version.ifEmpty { null }
                    )
                }
                StreamProtocol.CMD_STOP -> GameSession.stop(
                    ServerService.instance ?: com.movtery.zalithlauncher.context.GlobalContext
                )
            }
        }

        private fun replyMeta() {
            sendMeta(gson.toJson(currentMeta()))
        }

        fun close(reason: String) {
            if (!closed.getAndSet(true)) {
                if (reason.isNotEmpty()) {
                    runCatching {
                        StreamProtocol.writeFrame(output, StreamProtocol.TYPE_BYE, reason)
                    }
                }
                runCatching { socket.close() }
            }
        }
    }
    // endregion
}
