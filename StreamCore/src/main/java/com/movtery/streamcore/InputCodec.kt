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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 输入事件编码。客户端把所有输入打包进一个 `INPUT` 帧，
 * 服务端解码后转成 GLFW / LWJGL 事件。
 *
 * 布局：`[byte eventType][payload...]`，多个事件可以直接首尾相接放在同一个帧里，
 * 服务端按顺序处理，减少每帧的 TCP 开销。
 */
object InputCodec {
    // region 事件类型
    /** 指针事件，见 [Pointer] */
    const val EVENT_POINTER = 1

    /** 键盘按键：int glfwKey, byte action(1=按下/0=抬起), int mods */
    const val EVENT_KEY = 2

    /** 滚轮：float x, float y */
    const val EVENT_SCROLL = 3

    /** 文本输入（输入法）：UTF-8 字符串 */
    const val EVENT_TEXT = 4

    /** 鼠标模式：byte grabbed（1 = 抓取/游戏内视角，0 = 释放） */
    const val EVENT_GRAB = 5

    /** 指针热区点击（屏幕绝对坐标比例）：float xRatio, float yRatio, byte button */
    const val EVENT_TAP = 6

    /** 请求关键帧 */
    const val EVENT_KEYFRAME_REQUEST = 7
    // endregion

    // region 指针动作
    const val POINTER_DOWN = 0
    const val POINTER_MOVE = 1
    const val POINTER_UP = 2
    const val POINTER_CANCEL = 3
    // endregion

    /**
     * 指针事件
     * @param pointerId 指针 id，服务端据此区分多指
     * @param action 见 POINTER_*
     * @param xRatio 相对画面宽度的比例 0..1
     * @param yRatio 相对画面高度的比例 0..1
     * @param isDelta 为 true 时 [xRatio]/[yRatio] 表示相对位移（抓取模式下转视角用）
     * @param button 鼠标按键掩码（1=左键，2=右键，4=中键），0 表示无按键
     */
    data class Pointer(
        val pointerId: Int,
        val action: Int,
        val xRatio: Float,
        val yRatio: Float,
        val isDelta: Boolean = false,
        val button: Int = 0
    )

    data class Key(
        val glfwKey: Int,
        val isDown: Boolean,
        val mods: Int = 0
    )

    data class Scroll(val x: Float, val y: Float)
    data class Text(val text: String)
    data class Grab(val grabbed: Boolean)
    data class Tap(val xRatio: Float, val yRatio: Float, val button: Int = 0)

    /**
     * 事件编码器，内部复用缓冲区
     */
    class Encoder {
        private val buffer = ByteArrayOutputStream(128)
        private val out = DataOutputStream(buffer)

        fun pointer(pointer: Pointer): Encoder {
            out.writeByte(EVENT_POINTER)
            out.writeInt(pointer.pointerId)
            out.writeByte(pointer.action)
            out.writeFloat(pointer.xRatio)
            out.writeFloat(pointer.yRatio)
            out.writeBoolean(pointer.isDelta)
            out.writeByte(pointer.button)
            return this
        }

        fun key(key: Key): Encoder {
            out.writeByte(EVENT_KEY)
            out.writeInt(key.glfwKey)
            out.writeByte(if (key.isDown) 1 else 0)
            out.writeInt(key.mods)
            return this
        }

        fun scroll(scroll: Scroll): Encoder {
            out.writeByte(EVENT_SCROLL)
            out.writeFloat(scroll.x)
            out.writeFloat(scroll.y)
            return this
        }

        fun text(text: Text): Encoder {
            val bytes = text.text.toByteArray(Charsets.UTF_8)
            out.writeByte(EVENT_TEXT)
            out.writeInt(bytes.size)
            out.write(bytes)
            return this
        }

        fun grab(grabbed: Boolean): Encoder {
            out.writeByte(EVENT_GRAB)
            out.writeByte(if (grabbed) 1 else 0)
            return this
        }

        fun tap(tap: Tap): Encoder {
            out.writeByte(EVENT_TAP)
            out.writeFloat(tap.xRatio)
            out.writeFloat(tap.yRatio)
            out.writeByte(tap.button)
            return this
        }

        fun keyframeRequest(): Encoder {
            out.writeByte(EVENT_KEYFRAME_REQUEST)
            return this
        }

        fun isEmpty(): Boolean = buffer.size() == 0

        /**
         * 取出当前累积的所有事件并清空缓冲区
         */
        fun drain(): ByteArray {
            val bytes = buffer.toByteArray()
            buffer.reset()
            return bytes
        }
    }

    /**
     * 事件解码器，回调在调用线程（服务端网络线程）上同步触发，
     * 调用方负责切到合适线程再投递到游戏。
     */
    class Decoder(private val handler: Handler) {
        interface Handler {
            fun onPointer(pointer: Pointer)
            fun onKey(key: Key)
            fun onScroll(scroll: Scroll)
            fun onText(text: Text)
            fun onGrab(grabbed: Boolean)
            fun onTap(tap: Tap)
            fun onKeyframeRequest()
        }

        @Throws(IOException::class)
        fun decode(payload: ByteArray) {
            val input = DataInputStream(ByteArrayInputStream(payload))
            while (true) {
                val type = try {
                    input.readUnsignedByte()
                } catch (_: java.io.EOFException) {
                    return
                }
                when (type) {
                    EVENT_POINTER -> handler.onPointer(
                        Pointer(
                            pointerId = input.readInt(),
                            action = input.readUnsignedByte(),
                            xRatio = input.readFloat(),
                            yRatio = input.readFloat(),
                            isDelta = input.readBoolean(),
                            button = input.readUnsignedByte()
                        )
                    )
                    EVENT_KEY -> handler.onKey(
                        Key(
                            glfwKey = input.readInt(),
                            isDown = input.readUnsignedByte() != 0,
                            mods = input.readInt()
                        )
                    )
                    EVENT_SCROLL -> handler.onScroll(Scroll(input.readFloat(), input.readFloat()))
                    EVENT_TEXT -> {
                        val length = input.readInt()
                        if (length < 0 || length > 4096) throw IOException("Invalid text length: $length")
                        val bytes = ByteArray(length)
                        input.readFully(bytes)
                        handler.onText(Text(String(bytes, Charsets.UTF_8)))
                    }
                    EVENT_GRAB -> handler.onGrab(input.readUnsignedByte() != 0)
                    EVENT_TAP -> handler.onTap(
                        Tap(
                            xRatio = input.readFloat(),
                            yRatio = input.readFloat(),
                            button = input.readUnsignedByte()
                        )
                    )
                    EVENT_KEYFRAME_REQUEST -> handler.onKeyframeRequest()
                    else -> throw IOException("Unknown input event: $type")
                }
            }
        }
    }
}
