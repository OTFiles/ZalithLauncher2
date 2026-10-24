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
import com.movtery.streamcore.InputCodec
import com.movtery.streamcore.Keycodes
import com.movtery.zalithlauncher.game.input.LWJGLCharSender
import com.movtery.zalithlauncher.setting.AllSettings
import org.lwjgl.glfw.CallbackBridge

private const val TAG = "RemoteInput"

/**
 * 把客户端发来的输入翻译成游戏能理解的 GLFW 事件。
 *
 * 服务端没有真实的触摸屏，所以这里完全复用启动器原有的注入通道
 * （[CallbackBridge]），只是事件源换成了网络。
 */
object RemoteInput : InputCodec.Decoder.Handler {
    /** 鼠标是否处于抓取（游戏内视角）模式 */
    @Volatile
    var grabbed: Boolean = false
        private set

    /** 虚拟指针位置（游戏坐标系） */
    private var cursorX = 0f
    private var cursorY = 0f

    /** 当前按下的鼠标按键掩码，用来处理多键同时按下 */
    private var buttonMask = 0
    private var lastPointerId = -1

    /**
     * 串流画面尺寸，用于把客户端的比例坐标换算成游戏坐标
     */
    @Volatile
    var streamWidth: Int = 1280
    @Volatile
    var streamHeight: Int = 720

    fun updateStreamSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            streamWidth = width
            streamHeight = height
        }
    }

    override fun onPointer(pointer: InputCodec.Pointer) {
        val x = pointer.xRatio.coerceIn(-4f, 4f) * streamWidth
        val y = pointer.yRatio.coerceIn(-4f, 4f) * streamHeight

        when (pointer.action) {
            InputCodec.POINTER_DOWN -> {
                lastPointerId = pointer.pointerId
                if (grabbed) {
                    //抓取模式下按下相当于直接点左键
                    setButton(Keycodes.MOUSE_BUTTON_LEFT, true)
                } else {
                    cursorX = x
                    cursorY = y
                    CallbackBridge.sendCursorPos(cursorX, cursorY)
                    if (pointer.button != 0) {
                        applyButtons(pointer.button)
                    } else {
                        setButton(Keycodes.MOUSE_BUTTON_LEFT, true)
                    }
                }
            }

            InputCodec.POINTER_MOVE -> {
                if (pointer.isDelta) {
                    //相对位移：抓取模式下用于转视角
                    CallbackBridge.sendCursorDelta(x, y)
                    cursorX += x
                    cursorY += y
                } else if (pointer.pointerId == lastPointerId || lastPointerId == -1) {
                    cursorX = x
                    cursorY = y
                    CallbackBridge.sendCursorPos(cursorX, cursorY)
                }
            }

            InputCodec.POINTER_UP, InputCodec.POINTER_CANCEL -> {
                if (pointer.action == InputCodec.POINTER_CANCEL) {
                    releaseAllButtons()
                } else if (grabbed) {
                    setButton(Keycodes.MOUSE_BUTTON_LEFT, false)
                } else {
                    applyButtons(0)
                }
                if (pointer.pointerId == lastPointerId) lastPointerId = -1
            }
        }
    }

    override fun onKey(key: InputCodec.Key) {
        CallbackBridge.setModifiers(key.glfwKey, key.isDown)
        CallbackBridge.sendKeyPress(
            key.glfwKey,
            CallbackBridge.getCurrentMods(),
            key.isDown
        )
    }

    override fun onScroll(scroll: InputCodec.Scroll) {
        CallbackBridge.sendScroll(scroll.x.toDouble(), scroll.y.toDouble())
    }

    override fun onText(text: InputCodec.Text) {
        text.text.forEach { char ->
            CallbackBridge.sendChar(char, CallbackBridge.getCurrentMods())
        }
    }

    override fun onGrab(grabbed: Boolean) {
        this.grabbed = grabbed
        if (!grabbed) {
            //退出抓取模式时把按键全部松开，避免视角卡住
            releaseAllButtons()
        }
        GameSession.updateGrabbed(grabbed)
    }

    override fun onTap(tap: InputCodec.Tap) {
        val x = tap.xRatio.coerceIn(0f, 1f) * streamWidth
        val y = tap.yRatio.coerceIn(0f, 1f) * streamHeight
        if (grabbed) {
            //抓取模式下绝对坐标无意义，按点击处理左键
            CallbackBridge.putMouseEvent(Keycodes.MOUSE_BUTTON_LEFT - 1)
            return
        }
        CallbackBridge.sendCursorPos(x, y)
        cursorX = x
        cursorY = y
        val glfwButton = when (tap.button) {
            Keycodes.MOUSE_BUTTON_RIGHT -> 1
            Keycodes.MOUSE_BUTTON_MIDDLE -> 2
            else -> 0
        }
        CallbackBridge.putMouseEventWithCoords(glfwButton, x, y)
    }

    override fun onKeyframeRequest() {
        ServerStreamEngine.instance.requestKeyFrame()
    }

    /**
     * 按掩码同步鼠标按键状态
     */
    private fun applyButtons(mask: Int) {
        val changed = mask xor buttonMask
        if (changed and Keycodes.MOUSE_BUTTON_LEFT != 0) {
            setButton(Keycodes.MOUSE_BUTTON_LEFT, mask and Keycodes.MOUSE_BUTTON_LEFT != 0)
        }
        if (changed and Keycodes.MOUSE_BUTTON_RIGHT != 0) {
            setButton(Keycodes.MOUSE_BUTTON_RIGHT, mask and Keycodes.MOUSE_BUTTON_RIGHT != 0)
        }
        if (changed and Keycodes.MOUSE_BUTTON_MIDDLE != 0) {
            setButton(Keycodes.MOUSE_BUTTON_MIDDLE, mask and Keycodes.MOUSE_BUTTON_MIDDLE != 0)
        }
    }

    private fun setButton(mask: Int, pressed: Boolean) {
        val glfwButton = when (mask) {
            Keycodes.MOUSE_BUTTON_LEFT -> 0
            Keycodes.MOUSE_BUTTON_RIGHT -> 1
            Keycodes.MOUSE_BUTTON_MIDDLE -> 2
            else -> return
        }
        if (pressed) buttonMask = buttonMask or mask else buttonMask = buttonMask and mask.inv()
        CallbackBridge.sendMouseButton(glfwButton, pressed)
    }

    fun releaseAllButtons() {
        if (buttonMask and Keycodes.MOUSE_BUTTON_LEFT != 0) setButton(Keycodes.MOUSE_BUTTON_LEFT, false)
        if (buttonMask and Keycodes.MOUSE_BUTTON_RIGHT != 0) setButton(Keycodes.MOUSE_BUTTON_RIGHT, false)
        if (buttonMask and Keycodes.MOUSE_BUTTON_MIDDLE != 0) setButton(Keycodes.MOUSE_BUTTON_MIDDLE, false)
        buttonMask = 0
        runCatching { CallbackBridge.resetInputState() }
            .onFailure { Log.w(TAG, "resetInputState failed", it) }
    }

    /**
     * 使用启动器现有的字符发送器输入文本（兼容 lwjgl3ify 等场景）
     */
    fun sendText(text: String) {
        text.forEach { char ->
            LWJGLCharSender.sendChar(char)
        }
    }

    /**
     * 打开/关闭抓取模式时同步设置里的“手势控制”相关状态
     */
    fun syncFromSettings() {
        //服务端永远不需要手势控制，避免误触
        runCatching { AllSettings.gestureControl.save(false) }
    }
}
