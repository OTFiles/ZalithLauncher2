/*
 * Zalith Launcher 2 - StreamCore
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

package com.movtery.streamcore

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 串流协议。
 *
 * 客户端连接后先发送 [Handshake]（UTF-8 JSON），服务端校验后回发 [ServerHello]，
 * 之后双端各自持续发送 [Frame]：
 *
 *  - 服务端 -> 客户端：`VIDEO`（H.264 Annex-B 访问单元）、`META`（运行状态）、`PING`
 *  - 客户端 -> 服务端：`INPUT`（输入事件）、`COMMAND`（远程指令）、`PING`
 *
 * 帧格式：`[1 byte type][4 byte payload length, big endian][payload]`
 */
object StreamProtocol {
    /** 协议版本，双端不一致时拒绝连接 */
    const val PROTOCOL_VERSION = 1

    /** 默认监听端口 */
    const val DEFAULT_PORT = 25580

    /** 单个负载的硬上限，避免异常报文把内存吃满（16MB） */
    const val MAX_FRAME_PAYLOAD = 16 * 1024 * 1024

    /** 默认视频宽高 */
    const val DEFAULT_STREAM_WIDTH = 1280
    const val DEFAULT_STREAM_HEIGHT = 720

    // region 帧类型
    /** 握手（客户端 -> 服务端），负载为 UTF-8 JSON */
    const val TYPE_HELLO = 1

    /** 握手应答（服务端 -> 客户端），负载为 UTF-8 JSON */
    const val TYPE_WELCOME = 2

    /** 视频数据（服务端 -> 客户端），负载为 H.264 Annex-B 访问单元 */
    const val TYPE_VIDEO = 3

    /** 运行状态（服务端 -> 客户端），负载为 UTF-8 JSON */
    const val TYPE_META = 4

    /** 输入事件（客户端 -> 服务端），负载为二进制已编码事件 */
    const val TYPE_INPUT = 5

    /** 心跳，负载可为空 */
    const val TYPE_PING = 6

    /** 心跳应答，负载可为空 */
    const val TYPE_PONG = 7

    /** 服务端主动断开，负载为 UTF-8 原因 */
    const val TYPE_BYE = 8

    /** 客户端请求关键帧（网络抖动丢帧后恢复用），负载为空 */
    const val TYPE_KEYFRAME_REQUEST = 9

    /** 远程指令（客户端 -> 服务端），负载为 UTF-8 JSON */
    const val TYPE_COMMAND = 10
    // endregion

    // region 运行状态
    /** 空闲，尚未启动游戏 */
    const val STATE_IDLE = "idle"

    /** 正在启动游戏 */
    const val STATE_LAUNCHING = "launching"

    /** 游戏运行中 */
    const val STATE_RUNNING = "running"

    /** 游戏已退出 */
    const val STATE_STOPPED = "stopped"

    /** 发生错误 */
    const val STATE_ERROR = "error"
    // endregion

    // region 远程指令
    const val CMD_LIST_VERSIONS = "list_versions"
    const val CMD_LAUNCH = "launch"
    const val CMD_STOP = "stop"
    const val CMD_STATUS = "status"
    // endregion

    /**
     * 写一个完整帧
     */
    @Throws(IOException::class)
    fun writeFrame(
        out: OutputStream,
        type: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size
    ) {
        if (length > MAX_FRAME_PAYLOAD) throw IOException("Frame payload too large: $length")
        val header = ByteArray(5)
        header[0] = type.toByte()
        header[1] = (length ushr 24).toByte()
        header[2] = (length ushr 16).toByte()
        header[3] = (length ushr 8).toByte()
        header[4] = length.toByte()
        // 同一把锁避免多线程交错写出半个帧
        synchronized(out) {
            out.write(header)
            if (length > 0) out.write(payload, offset, length)
            out.flush()
        }
    }

    @Throws(IOException::class)
    fun writeFrame(out: OutputStream, type: Int, payload: String) {
        writeFrame(out, type, payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * 读取一个完整帧，流结束返回 null
     */
    @Throws(IOException::class)
    fun readFrame(input: InputStream): Frame? {
        val header = ByteArray(5)
        if (!readFully(input, header, 0, 5)) return null
        val type = header[0].toInt() and 0xFF
        val length = ((header[1].toInt() and 0xFF) shl 24) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 8) or
                (header[4].toInt() and 0xFF)
        if (length < 0 || length > MAX_FRAME_PAYLOAD) {
            throw IOException("Invalid frame length: $length")
        }
        val payload = ByteArray(length)
        if (length > 0 && !readFully(input, payload, 0, length)) {
            throw EOFException("Unexpected end of stream inside frame payload")
        }
        return Frame(type, payload)
    }

    /**
     * 尽力读满 [length] 字节
     * @return 流在读取任何字节前就结束时返回 false
     */
    @Throws(IOException::class)
    fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) {
                if (total == 0) return false
                throw EOFException("Unexpected end of stream after $total/$length bytes")
            }
            total += read
        }
        return true
    }

    class Frame(val type: Int, val payload: ByteArray) {
        fun asString(): String = String(payload, Charsets.UTF_8)
    }

    /**
     * 客户端 -> 服务端
     * @param password 服务端设置的访问口令，空串表示不校验
     * @param maxBitrate 客户端能接受的最高码率（kbps），0 表示不限制
     */
    data class Handshake(
        val version: Int = PROTOCOL_VERSION,
        val password: String = "",
        val clientName: String = "",
        val maxBitrate: Int = 0
    )

    /**
     * 服务端 -> 客户端
     * @param ok 握手是否通过
     * @param reason ok 为 false 时的原因
     */
    data class ServerHello(
        val version: Int = PROTOCOL_VERSION,
        val ok: Boolean = true,
        val reason: String = "",
        val serverName: String = "",
        val width: Int = DEFAULT_STREAM_WIDTH,
        val height: Int = DEFAULT_STREAM_HEIGHT
    )

    /**
     * 运行状态
     * @param state 见 STATE_*
     * @param versions 已安装的版本列表（仅在响应 [CMD_LIST_VERSIONS] 时下发）
     */
    data class Meta(
        val state: String = STATE_IDLE,
        val message: String = "",
        val width: Int = DEFAULT_STREAM_WIDTH,
        val height: Int = DEFAULT_STREAM_HEIGHT,
        val fps: Int = 0,
        val bitrateKbps: Int = 0,
        val grabbed: Boolean = false,
        val currentVersion: String = "",
        val versions: List<String> = emptyList()
    )

    /**
     * 远程指令
     */
    data class Command(
        val cmd: String = "",
        val version: String = "",
        val arg: String = ""
    )
}
