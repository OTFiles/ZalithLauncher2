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

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue

/**
 * H.264 解码器（硬件优先）。
 *
 * 服务端发来的码流是 Annex-B 格式，MediaCodec 可以直接吃；
 * 输出直接绘制到 [Surface]（SurfaceView），因此渲染没有 CPU 拷贝。
 */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    @Volatile
    private var running = false
    private var decodeThread: Thread? = null

    /** 已解码帧数 */
    @Volatile
    var decodedFrames: Int = 0
        private set

    /** 是否已经拿到可解码的首帧（关键帧） */
    @Volatile
    private var sawKeyFrame = false

    /** 解码错误回调，用于让上层请求关键帧 */
    var onDecodeError: (() -> Unit)? = null

    fun start(width: Int, height: Int) {
        this.width = width
        this.height = height
        running = true
        decodeThread = Thread({ decodeLoop() }, "ZalithVideoDecoder").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 丢入一个访问单元
     */
    fun submit(data: ByteArray) {
        if (!running) return
        //队列满说明客户端处理不过来，丢弃最旧的帧（实时性优先于完整性）
        if (!queue.offer(data)) {
            queue.poll()
            queue.offer(data)
        }
    }

    private fun ensureCodec(): MediaCodec? {
        codec?.let { return it }
        return runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            created
        }.getOrElse {
            null
        }
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        var errors = 0
        while (running) {
            val data = try {
                queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            val codec = ensureCodec() ?: continue

            if (data != null) {
                val isKey = AnnexB.containsKeyFrame(data)
                if (isKey) sawKeyFrame = true
                //没拿到关键帧之前的 P 帧解码器也解不出来，直接丢，省电
                if (!sawKeyFrame) continue
                runCatching {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: return@runCatching
                        buffer.clear()
                        buffer.put(data)
                        codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
                        errors = 0
                    }
                }.onFailure {
                    if (++errors > 20) {
                        onDecodeError?.invoke()
                        errors = 0
                    }
                }
            }

            //尽量把输出都取干净
            while (true) {
                val outIndex = try {
                    codec.dequeueOutputBuffer(info, 0)
                } catch (_: IllegalStateException) {
                    return
                }
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex >= 0 -> {
                        //render = true，直接画到 SurfaceView
                        codec.releaseOutputBuffer(outIndex, true)
                        decodedFrames++
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        decodeThread?.interrupt()
        decodeThread = null
        queue.clear()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
