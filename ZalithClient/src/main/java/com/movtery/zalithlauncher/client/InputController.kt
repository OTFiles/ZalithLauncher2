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
 * 两种模式：
 *  - [TouchMode.CAPTURE]（默认）：单指滑动 = 转视角（相对位移），屏幕中央当作准星，
 *    这是玩 MC 最舒服的方式；双指滑动 = 后退/前进（模拟 W/S 不方便，这里做成滚轮）。
 *  - [TouchMode.DIRECT]：手指位置就是鼠标位置，适合点菜单、合成、开箱子。
 *  - [TouchMode.WALK]：屏幕左侧拖动 = WASD 移动，右侧拖动 = 转视角（默认推荐的手机玩法）。
 */
class InputController(private val connection: StreamConnection) {
    enum class TouchMode { CAPTURE, DIRECT, WALK }

    var mode: TouchMode = TouchMode.WALK
    /** 视角灵敏度，1.0 为默认 */
    var sensitivity: Float = 1.0f
    /** 客户端画面宽高，用于把像素位移换算成比例 */
    var videoWidth: Int = 1280
    var videoHeight: Int = 720

    private var lastX = 0f
    private var lastY = 0f
    private var primaryPointerId = -1
    private var secondaryPointerId = -1

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
        if (primaryPointerId == -1) {
            primaryPointerId = pointerId
            lastX = x
            lastY = y
        } else if (secondaryPointerId == -1) {
            secondaryPointerId = pointerId
        }

        when (mode) {
            TouchMode.DIRECT -> {
                val rx = (x / viewWidth).coerceIn(0f, 1f)
                val ry = (y / viewHeight).coerceIn(0f, 1f)
                connection.sendPointer(
                    InputCodec.Pointer(
                        pointerId = pointerId,
                        action = InputCodec.POINTER_DOWN,
                        xRatio = rx,
                        yRatio = ry
                    )
                )
            }
            TouchMode.CAPTURE -> Unit
            TouchMode.WALK -> {
                //左半屏当作虚拟摇杆，按下瞬间不移动
            }
        }
    }

    fun onTouchMove(pointerId: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        when (mode) {
            TouchMode.DIRECT -> {
                val rx = (x / viewWidth).coerceIn(0f, 1f)
                val ry = (y / viewHeight).coerceIn(0f, 1f)
                connection.sendPointer(
                    InputCodec.Pointer(
                        pointerId = pointerId,
                        action = InputCodec.POINTER_MOVE,
                        xRatio = rx,
                        yRatio = ry
                    )
                )
            }
            TouchMode.CAPTURE -> {
                if (pointerId != primaryPointerId) return
                val dx = (x - lastX) * sensitivity
                val dy = (y - lastY) * sensitivity
                lastX = x
                lastY = y
                if (dx == 0f && dy == 0f) return
                //服务端按像素位移处理，这里直接给像素增量
                connection.sendPointer(
                    InputCodec.Pointer(
                        pointerId = pointerId,
                        action = InputCodec.POINTER_MOVE,
                        xRatio = dx,
                        yRatio = dy,
                        isDelta = true
                    )
                )
            }
            TouchMode.WALK -> {
                if (pointerId == primaryPointerId) {
                    //左半屏：虚拟摇杆 -> WASD
                    val centerX = viewWidth * 0.22f
                    val centerY = viewHeight * 0.68f
                    val dx = x - centerX
                    val dy = y - centerY
                    val deadZone = viewWidth * 0.05f
                    setKey(Keycodes.KEY_W, dy < -deadZone)
                    setKey(Keycodes.KEY_S, dy > deadZone)
                    setKey(Keycodes.KEY_A, dx < -deadZone)
                    setKey(Keycodes.KEY_D, dx > deadZone)
                } else if (pointerId == secondaryPointerId) {
                    val dx = (x - lastX) * sensitivity
                    val dy = (y - lastY) * sensitivity
                    lastX = x
                    lastY = y
                    if (dx == 0f && dy == 0f) return
                    connection.sendPointer(
                        InputCodec.Pointer(
                            pointerId = pointerId,
                            action = InputCodec.POINTER_MOVE,
                            xRatio = dx,
                            yRatio = dy,
                            isDelta = true
                        )
                    )
                }
            }
        }
    }

    fun onTouchUp(pointerId: Int, x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        when (mode) {
            TouchMode.DIRECT -> {
                val rx = (x / viewWidth).coerceIn(0f, 1f)
                val ry = (y / viewHeight).coerceIn(0f, 1f)
                connection.sendPointer(
                    InputCodec.Pointer(
                        pointerId = pointerId,
                        action = InputCodec.POINTER_UP,
                        xRatio = rx,
                        yRatio = ry
                    )
                )
            }
            TouchMode.CAPTURE -> {
                if (pointerId == primaryPointerId) {
                    connection.sendPointer(
                        InputCodec.Pointer(
                            pointerId = pointerId,
                            action = InputCodec.POINTER_UP,
                            xRatio = 0f,
                            yRatio = 0f
                        )
                    )
                }
            }
            TouchMode.WALK -> {
                if (pointerId == primaryPointerId) {
                    setKey(Keycodes.KEY_W, false)
                    setKey(Keycodes.KEY_S, false)
                    setKey(Keycodes.KEY_A, false)
                    setKey(Keycodes.KEY_D, false)
                }
            }
        }
        if (pointerId == primaryPointerId) primaryPointerId = -1
        if (pointerId == secondaryPointerId) secondaryPointerId = -1
    }

    fun onTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int, rightClick: Boolean) {
        val rx = (x / viewWidth).coerceIn(0f, 1f)
        val ry = (y / viewHeight).coerceIn(0f, 1f)
        if (mode == TouchMode.DIRECT && !grabbed) {
            connection.sendTap(
                InputCodec.Tap(
                    xRatio = rx,
                    yRatio = ry,
                    button = if (rightClick) Keycodes.MOUSE_BUTTON_RIGHT else Keycodes.MOUSE_BUTTON_LEFT
                )
            )
        } else {
            //非直接模式下，点击交给服务端当作左/右键点击
            connection.sendTap(
                InputCodec.Tap(
                    xRatio = 0.5f,
                    yRatio = 0.5f,
                    button = if (rightClick) Keycodes.MOUSE_BUTTON_RIGHT else Keycodes.MOUSE_BUTTON_LEFT
                )
            )
        }
    }

    fun onScroll(dy: Float) {
        connection.sendScroll(InputCodec.Scroll(0f, dy))
    }

    fun onLongPress() {
        //长按 = 按住左键（挖掘）
        connection.sendPointer(
            InputCodec.Pointer(
                pointerId = 99,
                action = InputCodec.POINTER_DOWN,
                xRatio = 0f,
                yRatio = 0f
            )
        )
    }

    fun onLongPressEnd() {
        connection.sendPointer(
            InputCodec.Pointer(
                pointerId = 99,
                action = InputCodec.POINTER_UP,
                xRatio = 0f,
                yRatio = 0f
            )
        )
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
            KeyEvent.KEYCODE_SHIFT_LEFT -> Keycodes.KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_SHIFT_RIGHT -> Keycodes.KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT -> Keycodes.KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_CTRL_RIGHT -> Keycodes.KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_ALT_LEFT -> Keycodes.KEY_LEFT_ALT
            KeyEvent.KEYCODE_ALT_RIGHT -> Keycodes.KEY_LEFT_ALT
            else -> null
        }
    }
}
