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

import com.google.gson.Gson
import com.movtery.streamcore.InputCodec
import com.movtery.streamcore.StreamProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 客户端连接。一个连接 = 一条 TCP，视频下发与输入上行共用
 * （串流场景下这样最省事，也不会被运营商/防火墙挡端口）。
 */
class StreamConnection(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val listener: Listener
) {
    private val gson = Gson()

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var readThread: Thread? = null
    private var pingThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val encoder = InputCodec.Encoder()

    var videoWidth: Int = StreamProtocol.DEFAULT_STREAM_WIDTH
        private set
    var videoHeight: Int = StreamProtocol.DEFAULT_STREAM_HEIGHT
        private set

    /** 最近一次收到视频的时间，用于判断画面是否卡死 */
    private val lastVideoAt = AtomicLong(0)

    interface Listener {
        fun onConnected(hello: StreamProtocol.ServerHello)
        fun onVideo(data: ByteArray)
        fun onAudio(data: ByteArray)
        fun onMeta(meta: StreamProtocol.Meta)
        fun onDisconnected(reason: String)
    }

    fun isConnected(): Boolean = running.get()

    /**
     * 同步连接 + 握手，耗时操作，请在工作线程调用
     */
    @Throws(IOException::class)
    fun connect(): StreamProtocol.ServerHello {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 8000)
        s.soTimeout = 8000
        socket = s
        val ins = BufferedInputStream(s.getInputStream(), 256 * 1024)
        val outs = BufferedOutputStream(s.getOutputStream(), 32 * 1024)
        input = ins
        output = outs

        val handshake = StreamProtocol.Handshake(
            version = StreamProtocol.PROTOCOL_VERSION,
            password = password,
            clientName = android.os.Build.MODEL ?: "ZL2 Client",
            maxBitrate = 0
        )
        StreamProtocol.writeFrame(outs, StreamProtocol.TYPE_HELLO, gson.toJson(handshake))

        val frame = StreamProtocol.readFrame(ins)
            ?: throw IOException("Server closed the connection during handshake")
        if (frame.type != StreamProtocol.TYPE_WELCOME) {
            throw IOException("Unexpected handshake reply: ${frame.type}")
        }
        val hello = gson.fromJson(frame.asString(), StreamProtocol.ServerHello::class.java)
            ?: throw IOException("Malformed handshake reply")
        if (!hello.ok) {
            throw IOException(hello.reason.ifEmpty { "Rejected by the server" })
        }
        videoWidth = hello.width
        videoHeight = hello.height

        s.soTimeout = 0
        running.set(true)
        lastVideoAt.set(System.currentTimeMillis())
        readThread = Thread({ readLoop() }, "ZalithClientRead").apply {
            isDaemon = true
            start()
        }
        pingThread = Thread({ pingLoop() }, "ZalithClientPing").apply {
            isDaemon = true
            start()
        }
        return hello
    }

    private fun readLoop() {
        var reason = "Disconnected"
        try {
            while (running.get()) {
                val frame = StreamProtocol.readFrame(input!!) ?: break
                when (frame.type) {
                    StreamProtocol.TYPE_VIDEO -> {
                        lastVideoAt.set(System.currentTimeMillis())
                        listener.onVideo(frame.payload)
                    }
                    StreamProtocol.TYPE_AUDIO -> listener.onAudio(frame.payload)
                    StreamProtocol.TYPE_META -> {
                        runCatching {
                            gson.fromJson(frame.asString(), StreamProtocol.Meta::class.java)
                        }.getOrNull()?.let { meta ->
                            if (meta.width > 0 && meta.height > 0) {
                                videoWidth = meta.width
                                videoHeight = meta.height
                            }
                            listener.onMeta(meta)
                        }
                    }
                    StreamProtocol.TYPE_PING -> sendFrame(StreamProtocol.TYPE_PONG, ByteArray(0))
                    StreamProtocol.TYPE_BYE -> {
                        reason = frame.asString().ifEmpty { "Server closed the connection" }
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: IOException) {
            reason = e.message ?: "Network error"
        } catch (e: Throwable) {
            reason = e.message ?: e.javaClass.simpleName
        } finally {
            if (running.getAndSet(false)) {
                closeQuietly()
                listener.onDisconnected(reason)
            }
        }
    }

    private fun pingLoop() {
        while (running.get()) {
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
                return
            }
            if (!running.get()) return
            sendFrame(StreamProtocol.TYPE_PING, ByteArray(0))
            //超过 8 秒没有任何画面，请求关键帧恢复
            if (System.currentTimeMillis() - lastVideoAt.get() > 8000) {
                requestKeyFrame()
            }
        }
    }

    // region 发送输入

    fun sendVideoReset() {
        //无操作，保留接口以便将来插入码率自适应
    }

    fun requestKeyFrame() {
        runCatching { sendFrame(StreamProtocol.TYPE_KEYFRAME_REQUEST, ByteArray(0)) }
    }

    fun sendPointer(pointer: InputCodec.Pointer) {
        encoder.pointer(pointer)
        flushInput()
    }

    fun sendKey(key: InputCodec.Key) {
        encoder.key(key)
        flushInput()
    }

    fun sendScroll(scroll: InputCodec.Scroll) {
        encoder.scroll(scroll)
        flushInput()
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        encoder.text(InputCodec.Text(text))
        flushInput()
    }

    fun sendGrab(grabbed: Boolean) {
        encoder.grab(grabbed)
        flushInput()
    }

    fun sendTap(tap: InputCodec.Tap) {
        encoder.tap(tap)
        flushInput()
    }

    /**
     * 发送远程指令（启动游戏等）
     */
    fun sendCommand(command: StreamProtocol.Command) {
        runCatching {
            sendFrame(StreamProtocol.TYPE_COMMAND, gson.toJson(command))
        }
    }

    private fun flushInput() {
        if (!running.get()) return
        val payload = encoder.drain()
        if (payload.isEmpty()) return
        sendFrame(StreamProtocol.TYPE_INPUT, payload)
    }

    private fun sendFrame(type: Int, payload: ByteArray) {
        val outs = output ?: return
        runCatching {
            StreamProtocol.writeFrame(outs, type, payload)
        }.onFailure {
            if (running.getAndSet(false)) {
                closeQuietly()
                listener.onDisconnected(it.message ?: "Write failed")
            }
        }
    }

    private fun sendFrame(type: Int, payload: String) {
        sendFrame(type, payload.toByteArray(Charsets.UTF_8))
    }
    // endregion

    fun close(reason: String = "Closed by user") {
        if (running.getAndSet(false)) {
            closeQuietly()
        } else {
            closeQuietly()
        }
        readThread?.interrupt()
        pingThread?.interrupt()
        readThread = null
        pingThread = null
        listener.onDisconnected(reason)
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
