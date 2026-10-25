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

import android.view.KeyEvent
import com.movtery.streamcore.InputCodec
import com.movtery.streamcore.Keycodes

/**
 * 触屏手势 -> 串流输入。
 *
 * 三种模式（服务端只需理解"绝对坐标比例"和"相对位移比例"两种指针事件）：
 *  - [TouchMode.WALK]（默认，推荐）：屏幕左半边拖动 = WASD 摇杆，右半边拖动 = 转视角。
 *  - [TouchMode.CAPTURE]：整屏拖动 = 转视角，适合主要靠虚拟按键玩。
 *  - [TouchMode.DIRECT]：手指位置就是鼠标位置，适合点菜单 / 合成 / 开箱子。
 *
 * 重要约定：所有坐标都发"比例"而不是像素，这样客户端分辨率与服务端
 * 串流分辨率不一致时也能正确映射。
 */
class InputController(private val connection: StreamConnection) {
    enum class TouchMode { WALK, CAPTURE, DIRECT }

    var mode: TouchMode = TouchMode.WALK
    /** 视角灵敏度，1.0 为默认 */
    var sensitivity: Float = 1.0f

    private enum class Role { MOVE, LOOK }

    /** 每个指针的角色与上一次位置 */
    private class PointerState(var role: Role, var lastX: Float, var lastY: Float)

    private val pointers = HashMap<Int, PointerState>()

    /** 虚拟按键当前状态，避免重复发按下事件 */
    private val pressedKeys = HashSet<Int>()

    var grabbed: Boolean = false
        private set

    fun setGrabbed(value: Boolean) {
        if (grabbed == value) return
        grabbed = value
        connection.sendGrab(value)
    }

    // region 触摸

    fun onTouchDown(pointerId: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val w = viewWidth.coerceAtLeast(1)
        val h = viewHeight.coerceAtLeast(1)
        val role = when (mode) {
            TouchMode.WALK -> if (x < w * 0.5f) Role.MOVE else Role.LOOK
            else -> Role.LOOK
        }
        pointers[pointerId] = PointerState(role, x, y)

        when {
            mode == TouchMode.DIRECT -> {
                connection.sendPointer(
                    InputCodec.Pointer(
                        pointerId = pointerId,
                        action = InputCodec.POINTER_DOWN,
                        xRatio = (x / w).coerceIn(0f, 1f),
                        yRatio = (y / h).coerceIn(0f, 1f)
                    )
                )
            }
            //WALK 模式下左半边是摇杆，不产生指针事件
            role == Role.MOVE -> Unit
            else -> Unit
        }
    }

    fun onTouchMove(pointerId: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val w = viewWidth.coerceAtLeast(1)
        val h = viewHeight.coerceAtLeast(1)
        val state = pointers[pointerId] ?: return

        if (mode == TouchMode.DIRECT) {
            connection.sendPointer(
                InputCodec.Pointer(
                    pointerId = pointerId,
                    action = InputCodec.POINTER_MOVE,
                    xRatio = (x / w).coerceIn(0f, 1f),
                    yRatio = (y / h).coerceIn(0f, 1f)
                )
            )
            state.lastX = x
            state.lastY = y
            return
        }

        if (state.role == Role.MOVE) {
            //左半屏虚拟摇杆 -> WASD
            val centerX = w * 0.22f
            val centerY = h * 0.70f
            val deadZone = w * 0.06f
            val dx = x - centerX
            val dy = y - centerY
            setKey(Keycodes.KEY_W, dy < -deadZone)
            setKey(Keycodes.KEY_S, dy > deadZone)
            setKey(Keycodes.KEY_A, dx < -deadZone)
            setKey(Keycodes.KEY_D, dx > deadZone)
            state.lastX = x
            state.lastY = y
            return
        }

        //转视角：发相对位移比例，服务端再换算成游戏像素
        val dx = x - state.lastX
        val dy = y - state.lastY
        state.lastX = x
        state.lastY = y
        if (dx == 0f && dy == 0f) return
        connection.sendPointer(
            InputCodec.Pointer(
                pointerId = pointerId,
                action = InputCodec.POINTER_MOVE,
                xRatio = dx * sensitivity / w,
                yRatio = dy * sensitivity / h,
                isDelta = true
            )
        )
    }

    fun onTouchUp(pointerId: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val w = viewWidth.coerceAtLeast(1)
        val h = viewHeight.coerceAtLeast(1)
        val state = pointers.remove(pointerId)

        if (mode == TouchMode.DIRECT) {
            connection.sendPointer(
                InputCodec.Pointer(
                    pointerId = pointerId,
                    action = InputCodec.POINTER_UP,
                    xRatio = (x / w).coerceIn(0f, 1f),
                    yRatio = (y / h).coerceIn(0f, 1f)
                )
            )
        } else if (state?.role == Role.MOVE) {
            setKey(Keycodes.KEY_W, false)
            setKey(Keycodes.KEY_S, false)
            setKey(Keycodes.KEY_A, false)
            setKey(Keycodes.KEY_D, false)
        }
    }

    fun onCancel() {
        releaseAllKeys()
        pointers.clear()
        connection.sendPointer(
            InputCodec.Pointer(
                pointerId = -1,
                action = InputCodec.POINTER_CANCEL,
                xRatio = 0f,
                yRatio = 0f
            )
        )
    }

    /**
     * 点击：直接模式下按该点位置点击，其它模式在准星位置点击
     */
    fun onTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int, rightClick: Boolean) {
        val w = viewWidth.coerceAtLeast(1)
        val h = viewHeight.coerceAtLeast(1)
        val direct = mode == TouchMode.DIRECT
        connection.sendTap(
            InputCodec.Tap(
                xRatio = if (direct) (x / w).coerceIn(0f, 1f) else 0.5f,
                yRatio = if (direct) (y / h).coerceIn(0f, 1f) else 0.5f,
                button = if (rightClick) Keycodes.MOUSE_BUTTON_RIGHT else Keycodes.MOUSE_BUTTON_LEFT
            )
        )
    }

    fun onScroll(dy: Float) {
        connection.sendScroll(InputCodec.Scroll(0f, dy))
    }
    // endregion

    // region 虚拟按键

    fun setKey(glfwKey: Int, pressed: Boolean) {
        if (pressed) {
            if (!pressedKeys.add(glfwKey)) return
        } else {
            if (!pressedKeys.remove(glfwKey)) return
        }
        connection.sendKey(InputCodec.Key(glfwKey = glfwKey, isDown = pressed))
    }

    fun tapKey(glfwKey: Int) {
        connection.sendKey(InputCodec.Key(glfwKey = glfwKey, isDown = true))
        connection.sendKey(InputCodec.Key(glfwKey = glfwKey, isDown = false))
    }

    fun releaseAllKeys() {
        pressedKeys.toList().forEach { setKey(it, false) }
    }

    /**
     * 物理键盘按键映射
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val glfw = androidKeyToGlfw(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) setKey(glfw, true)
            }
            KeyEvent.ACTION_UP -> setKey(glfw, false)
            else -> return false
        }
        return true
    }

    /**
     * 输入法文本
     */
    fun sendText(text: String) {
        connection.sendText(text)
    }
    // endregion

    companion object {
        /**
         * Android 键码 -> GLFW 键码，只覆盖键盘上常见的按键
         */
        fun androidKeyToGlfw(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_A -> Keycodes.KEY_A
            KeyEvent.KEYCODE_B -> Keycodes.KEY_B
            KeyEvent.KEYCODE_C -> Keycodes.KEY_C
            KeyEvent.KEYCODE_D -> Keycodes.KEY_D
            KeyEvent.KEYCODE_E -> Keycodes.KEY_E
            KeyEvent.KEYCODE_F -> Keycodes.KEY_F
            KeyEvent.KEYCODE_G -> Keycodes.KEY_G
            KeyEvent.KEYCODE_H -> Keycodes.KEY_H
            KeyEvent.KEYCODE_I -> Keycodes.KEY_I
            KeyEvent.KEYCODE_J -> Keycodes.KEY_J
            KeyEvent.KEYCODE_K -> Keycodes.KEY_K
            KeyEvent.KEYCODE_L -> Keycodes.KEY_L
            KeyEvent.KEYCODE_M -> Keycodes.KEY_M
            KeyEvent.KEYCODE_N -> Keycodes.KEY_N
            KeyEvent.KEYCODE_O -> Keycodes.KEY_O
            KeyEvent.KEYCODE_P -> Keycodes.KEY_P
            KeyEvent.KEYCODE_Q -> Keycodes.KEY_Q
            KeyEvent.KEYCODE_R -> Keycodes.KEY_R
            KeyEvent.KEYCODE_S -> Keycodes.KEY_S
            KeyEvent.KEYCODE_T -> Keycodes.KEY_T
            KeyEvent.KEYCODE_U -> Keycodes.KEY_U
            KeyEvent.KEYCODE_V -> Keycodes.KEY_V
            KeyEvent.KEYCODE_W -> Keycodes.KEY_W
            KeyEvent.KEYCODE_X -> Keycodes.KEY_X
            KeyEvent.KEYCODE_Y -> Keycodes.KEY_Y
            KeyEvent.KEYCODE_Z -> Keycodes.KEY_Z
            KeyEvent.KEYCODE_0 -> Keycodes.KEY_0
            KeyEvent.KEYCODE_1 -> Keycodes.KEY_1
            KeyEvent.KEYCODE_2 -> Keycodes.KEY_2
            KeyEvent.KEYCODE_3 -> Keycodes.KEY_3
            KeyEvent.KEYCODE_4 -> Keycodes.KEY_4
            KeyEvent.KEYCODE_5 -> Keycodes.KEY_5
            KeyEvent.KEYCODE_6 -> Keycodes.KEY_6
            KeyEvent.KEYCODE_7 -> Keycodes.KEY_7
            KeyEvent.KEYCODE_8 -> Keycodes.KEY_8
            KeyEvent.KEYCODE_9 -> Keycodes.KEY_9
            KeyEvent.KEYCODE_SPACE -> Keycodes.KEY_SPACE
            KeyEvent.KEYCODE_ENTER -> Keycodes.KEY_ENTER
            KeyEvent.KEYCODE_TAB -> Keycodes.KEY_TAB
            KeyEvent.KEYCODE_DEL -> Keycodes.KEY_BACKSPACE
            KeyEvent.KEYCODE_ESCAPE -> Keycodes.KEY_ESCAPE
            KeyEvent.KEYCODE_DPAD_UP -> Keycodes.KEY_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keycodes.KEY_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Keycodes.KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keycodes.KEY_RIGHT
            KeyEvent.KEYCODE_F1 -> Keycodes.KEY_F1
            KeyEvent.KEYCODE_F2 -> Keycodes.KEY_F2
            KeyEvent.KEYCODE_F3 -> Keycodes.KEY_F3
            KeyEvent.KEYCODE_F4 -> Keycodes.KEY_F4
            KeyEvent.KEYCODE_F5 -> Keycodes.KEY_F5
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Keycodes.KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Keycodes.KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Keycodes.KEY_LEFT_ALT
            else -> null
        }
    }
}
