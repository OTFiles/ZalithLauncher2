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

/**
 * Annex-B 码流工具。
 *
 * 与服务端 VideoEncoder 里的逻辑保持一致（那边不能共享，因为客户端模块
 * 不想依赖整个启动器代码）。
 */
internal object AnnexB {
    private const val NAL_IDR = 5

    /**
     * 把 Annex-B 字节流切分成 NAL 单元（含起始码）
     */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(i)
                    i += 3
                    continue
                }
                if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(data)
        val units = ArrayList<ByteArray>(starts.size)
        for (index in starts.indices) {
            val start = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1] else data.size
            units.add(data.copyOfRange(start, end))
        }
        return units
    }

    /**
     * 判断一段 Annex-B 数据里是否含 IDR（关键帧）
     */
    fun containsKeyFrame(data: ByteArray): Boolean {
        return split(data).any { unit ->
            val headerIndex = when {
                unit.size > 3 && unit[2] == 1.toByte() -> 3
                unit.size > 4 && unit[2] == 0.toByte() && unit[3] == 1.toByte() -> 4
                else -> -1
            }
            headerIndex > 0 && unit.size > headerIndex &&
                    (unit[headerIndex].toInt() and 0x1F) == NAL_IDR
        }
    }
}
