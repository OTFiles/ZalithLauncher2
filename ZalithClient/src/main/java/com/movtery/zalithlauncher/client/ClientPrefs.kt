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

import android.content.Context
import android.content.SharedPreferences


/**
 * 客户端本地记忆的连接参数与偏好
 */
object ClientPrefs {
    private const val FILE = "zl2_client"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastHost(context: Context): String = prefs(context).getString("host", "") ?: ""
    fun setLastHost(context: Context, value: String) = prefs(context).edit().putString("host", value).apply()

    fun lastPort(context: Context): String = prefs(context).getString("port", "25580") ?: "25580"
    fun setLastPort(context: Context, value: String) = prefs(context).edit().putString("port", value).apply()

    fun lastPassword(context: Context): String = prefs(context).getString("password", "") ?: ""
    fun setLastPassword(context: Context, value: String) = prefs(context).edit().putString("password", value).apply()

    fun touchMode(context: Context): Int = prefs(context).getInt("touchMode", InputController.TouchMode.WALK.ordinal)
    fun setTouchMode(context: Context, value: Int) = prefs(context).edit().putInt("touchMode", value).apply()

    fun sensitivity(context: Context): Float = prefs(context).getFloat("sensitivity", 1.0f)
    fun setSensitivity(context: Context, value: Float) = prefs(context).edit().putFloat("sensitivity", value).apply()

    fun showVirtualKeys(context: Context): Boolean = prefs(context).getBoolean("virtualKeys", true)
    fun setShowVirtualKeys(context: Context, value: Boolean) = prefs(context).edit().putBoolean("virtualKeys", value).apply()
}
