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

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "VideoEncoder"
private const val MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC

/** NAL 单元类型 */
private const val NAL_SPS = 7
private const val NAL_PPS = 8
private const val NAL_IDR = 5

/**
 * H.264 硬件编码器。
 *
 * 直接接受一个 input Surface，游戏渲染到该 Surface 后编码器自行取帧，
 * 无需 CPU 拷贝。输出为 Annex-B 码流，按"访问单元"（一个画面）切分后回调，
 * 客户端 MediaCodec 需要完整的 AU 才能正确解码。
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    bitrateKbps: Int,
    private val onAccessUnit: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val bitrate = bitrateKbps.coerceIn(500, 100_000) * 1000

    var codec: MediaCodec? = null
        private set

    var inputSurface: Surface? = null
        private set

    /** 已产出的帧数，用于判断游戏是否真正开始渲染 */
    val frameProduced = AtomicInteger(0)

    private var encoderThread: Thread? = null
    @Volatile
    private var running = false

    /** 关键帧请求标志 */
    @Volatile
    private var requestKeyFrame = false

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /**
     * 启动编码器并返回可供游戏渲染的 Surface，失败返回 null
     */
    fun start(): Surface? {
        return runCatching {
            val format = MediaFormat.createVideoFormat(MIME_H264, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                //实时低延迟优先
                setInteger(MediaFormat.KEY_LATENCY, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            val codec = createCodec()
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            this.codec = codec
            this.inputSurface = surface
            running = true
            startDrainLoop(codec)
            surface
        }.onFailure {
            Log.e(TAG, "Failed to start the H.264 encoder", it)
            onError(it)
        }.getOrNull()
    }

    /**
     * 优先选硬件编码器，不行再退回软件编码器。
     * 有些低端机的 OMX 编码器对 KEY_LATENCY 之类的键不认账，直接换一个。
     */
    private fun createCodec(): MediaCodec {
        val hardware = runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.firstOrNull { info ->
                info.isHardwareAccelerated &&
                        info.isEncoder &&
                        info.supportedTypes.any { it.equals(MIME_H264, ignoreCase = true) }
            }
        }.getOrNull()
        if (hardware != null) {
            runCatching {
                return MediaCodec.createByCodecName(hardware.name)
            }.onFailure { Log.w(TAG, "Hardware encoder ${hardware.name} failed, falling back", it) }
        }
        return MediaCodec.createEncoderByType(MIME_H264)
    }

    /**
     * 请求立刻产出一个关键帧（客户端重连或丢包恢复时调用）
     */
    fun forceKeyFrame() {
        requestKeyFrame = true
    }

    private fun startDrainLoop(codec: MediaCodec) {
        val thread = Thread({
            val info = MediaCodec.BufferInfo()
            var lastKeyFrameRequest = 0L
            while (running) {
                try {
                    if (requestKeyFrame && System.currentTimeMillis() - lastKeyFrameRequest > 300) {
                        requestKeyFrame = false
                        lastKeyFrameRequest = System.currentTimeMillis()
                        val params = android.os.Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        }
                        runCatching { codec.setParameters(params) }
                    }

                    val index = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            //部分编码器在这里才给出 sps/pps
                            readSpsPps(codec.outputFormat)
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val data = ByteArray(info.size)
                                buffer.get(data)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    //csd 只在格式变化时出现，缓存起来供新客户端使用
                                    cacheParameterSets(data)
                                } else {
                                    frameProduced.incrementAndGet()
                                    val isKeyFrame =
                                        (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                                                containsKeyFrame(data)
                                    //有些编码器不单独发 csd，参数集只出现在关键帧里
                                    if (isKeyFrame && (sps == null || pps == null)) {
                                        cacheParameterSets(data)
                                    }
                                    //关键帧前拼上 SPS/PPS，客户端从任意位置接入都能解码
                                    val payload = if (isKeyFrame) {
                                        parameterSets()?.let { sets -> sets + data } ?: data
                                    } else {
                                        data
                                    }
                                    onAccessUnit(payload)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (_: IllegalStateException) {
                    break
                } catch (e: Throwable) {
                    Log.e(TAG, "Encoder drain loop failed", e)
                    onError(e)
                    break
                }
            }
        }, "ZalithVideoEncoder")
        thread.start()
        encoderThread = thread
    }

    private fun readSpsPps(format: MediaFormat) {
        runCatching {
            val spsBuffer: ByteBuffer? = format.getByteBuffer("csd-0")
            val ppsBuffer: ByteBuffer? = format.getByteBuffer("csd-1")
            sps = spsBuffer?.let { bufferToArray(it) }
            pps = ppsBuffer?.let { bufferToArray(it) }
        }
    }

    private fun cacheParameterSets(data: ByteArray) {
        //csd 数据可能是 [startCode][nal]...[startCode][nal] 的形式
        val units = splitAnnexB(data)
        units.forEach { unit ->
            if (unit.isEmpty()) return@forEach
            when (unit[0].toInt() and 0x1F) {
                NAL_SPS -> sps = unit
                NAL_PPS -> pps = unit
            }
        }
    }

    private fun bufferToArray(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    fun stop() {
        running = false
        runCatching { encoderThread?.join(2000) }
        encoderThread = null
        runCatching {
            codec?.stop()
        }
        runCatching {
            codec?.release()
        }
        codec = null
        runCatching {
            inputSurface?.release()
        }
        inputSurface = null
    }

    /**
     * 当前的关键帧参数集（SPS+PPS），新客户端接入时先补发，
     * 否则从中间开始的码流无法解码。
     */
    fun parameterSets(): ByteArray? {
        val s = sps ?: return null
        val p = pps ?: return null
        val prefix = byteArrayOf(0, 0, 0, 1)
        return prefix + s.removePrefix4() + prefix + p.removePrefix4()
    }

    private fun ByteArray.removePrefix4(): ByteArray {
        return when {
            size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
                    this[2] == 0.toByte() && this[3] == 1.toByte() -> copyOfRange(4, size)
            size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
                    this[2] == 1.toByte() -> copyOfRange(3, size)
            else -> this
        }
    }

    companion object {
        /**
         * 把 Annex-B 字节流切分成 NAL 单元（含起始码）
         */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
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

        /** 判断一段 Annex-B 数据里是否含 IDR */
        fun containsKeyFrame(data: ByteArray): Boolean {
            return splitAnnexB(data).any { unit ->
                val headerIndex = when {
                    unit.size > 4 && unit[2] == 1.toByte() -> 3
                    unit.size > 3 && unit[2] == 0.toByte() && unit[3] == 1.toByte() -> 4
                    else -> -1
                }
                headerIndex > 0 && unit.size > headerIndex &&
                        (unit[headerIndex].toInt() and 0x1F) == NAL_IDR
            }
        }
    }
}
