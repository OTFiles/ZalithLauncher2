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

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioPipeline"
private const val BITRATE = 96_000
private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

/**
 * 音频编码管线：PCM16 48kHz 双声道 -> AAC。
 *
 * 音频来源有两种，由 [AudioSource] 决定：
 *  - [AudioSource.WAVE_PIPE]：拦截 OpenAL 输出。宿主设备**完全静音**，
 *    不需要任何权限，安卓 8+ 可用（推荐，服务端默认）。
 *  - [AudioSource.SYSTEM_CAPTURE]：AudioPlaybackCapture。宿主设备照常发声，
 *    需要安卓 10+ 且用户授权录屏/录音。
 *
 * 两条路径最后都汇到同一个 [MediaCodec] AAC 编码器，所以下游（网络分发）完全不用改。
 */
class AudioPipeline(
    private val source: AudioSource,
    private val onFrame: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit = {}
) {
    enum class AudioSource {
        /** no-op */
        OFF,
        /** 拦截 OpenAL 混音输出（wave 后端） */
        WAVE_PIPE,
        /** 系统播放采集（MediaProjection） */
        SYSTEM_CAPTURE
    }

    private val running = AtomicBoolean(false)
    private var encoder: MediaCodec? = null
    private var drainThread: Thread? = null

    /** 实时 PCM 喂入端的缓冲队列，避免读取线程阻塞编码器 */
    private val inputQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(64)

    private var pipeReader: AudioPipeReader? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    /** 实际生效的来源（用于回退后如实报告给客户端） */
    @Volatile
    var activeSource: AudioSource = AudioSource.OFF
        private set

    /** 需要提供给 AudioPipeReader 的 alsoft 配置文件 */
    var pipeConfigFile: java.io.File? = null
        private set

    /** 是否真的收到过 PCM（用于判断 wave 拦截有没有生效） */
    @Volatile
    var pcmReceived: Boolean = false
        private set

    /** 需要注入给游戏的 alsoft 环境变量 */
    fun pipeEnvOverrides(): Map<String, String> {
        val reader = pipeReader ?: return emptyMap()
        return mapOf(
            "ALSOFT_CONF" to reader.configFile.absolutePath
        )
    }

    fun start(mediaProjection: MediaProjection?): Boolean {
        if (running.get()) return true
        return when (source) {
            AudioSource.OFF -> false
            AudioSource.WAVE_PIPE -> startWavePipe()
            AudioSource.SYSTEM_CAPTURE -> startSystemCapture(mediaProjection)
        }
    }

    // region wave 管道

    /**
     * 拦截 OpenAL 输出。这是服务端默认方式：
     * 声音被写进管道，宿主设备听不到任何声音，也不需要权限。
     */
    private fun startWavePipe(): Boolean {
        val reader = AudioPipeReader(
            onPcm = { data, length -> offerPcm(data, length) },
            onFailure = onFailure
        )
        if (!reader.prepare()) {
            Log.w(TAG, "Wave pipe unavailable, audio disabled")
            return false
        }
        if (!startEncoder()) return false
        pipeReader = reader
        pipeConfigFile = reader.configFile
        //读取要等 OpenAL 打开写端，放到独立线程里阻塞即可
        reader.start()
        running.set(true)
        activeSource = AudioSource.WAVE_PIPE
        Log.i(TAG, "Audio source: OpenAL wave pipe (host stays silent)")
        return true
    }

    /**
     * 外部（[AudioPipeReader]）把 PCM 交进来
     */
    private fun offerPcm(data: ByteArray, length: Int) {
        pcmReceived = true
        if (!inputQueue.offer(data.copyOf(length))) {
            inputQueue.poll()
            inputQueue.offer(data.copyOf(length))
        }
    }
    // endregion

    // region 系统采集

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSystemCapture(projection: MediaProjection?): Boolean {
        if (projection == null) {
            Log.i(TAG, "No media projection token, system audio capture unavailable")
            return false
        }
        return runCatching {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioPipeSpec.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            @Suppress("MissingPermission")
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(
                    AudioRecord.getMinBufferSize(
                        AudioPipeSpec.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(AudioPipeSpec.SAMPLE_RATE)
                )
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized (missing RECORD_AUDIO?)")
                runCatching { record.release() }
                return false
            }

            if (!startEncoder()) {
                runCatching { record.release() }
                return false
            }

            audioRecord = record
            running.set(true)
            activeSource = AudioSource.SYSTEM_CAPTURE
            record.startRecording()
            recordThread = Thread({ recordLoop(record) }, "ZalithAudioRecord").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Audio source: system playback capture")
            true
        }.onFailure {
            Log.w(TAG, "Failed to start system audio capture", it)
            onFailure(it)
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun recordLoop(record: AudioRecord) {
        val buffer = ByteArray(16 * 1024)
        while (running.get()) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) {
                if (read < 0) Log.w(TAG, "AudioRecord.read error: $read")
                continue
            }
            offerPcm(buffer, read)
        }
    }
    // endregion

    // region AAC 编码

    private fun startEncoder(): Boolean {
        return runCatching {
            val format = MediaFormat.createAudioFormat(
                MIME_AAC,
                AudioPipeSpec.SAMPLE_RATE,
                AudioPipeSpec.CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MIME_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            drainThread = Thread({ encodeLoop(codec) }, "ZalithAudioEncode").apply {
                isDaemon = true
                start()
            }
            true
        }.onFailure {
            Log.w(TAG, "Failed to start the AAC encoder", it)
            onFailure(it)
        }.getOrDefault(false)
    }

    /**
     * 把队列里的 PCM 送进编码器，同时把编码结果发出去
     */
    private fun encodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val pcm = inputQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (pcm != null) {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)
                        if (input != null) {
                            input.clear()
                            val size = minOf(pcm.size, input.remaining())
                            input.put(pcm, 0, size)
                            codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                System.nanoTime() / 1000,
                                0
                            )
                        }
                    }
                }

                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outIndex < 0) break
                    val out: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        out.get(data)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            onFrame(data)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: IllegalStateException) {
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Audio encode failed", e)
                return
            }
        }
    }
    // endregion

    fun stop() {
        running.set(false)
        recordThread?.interrupt()
        recordThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        pipeReader?.stop()
        pipeReader = null
        pipeConfigFile = null
        drainThread?.interrupt()
        drainThread = null
        inputQueue.clear()
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        activeSource = AudioSource.OFF
    }

    /**
     * 彻底清理（含管道文件）
     */
    fun cleanup() {
        pipeReader?.cleanup()
        stop()
    }

    companion object {
        /**
         * 当前来源是否真的能工作（用于 UI 提示）
         */
        fun isSourceSupported(source: AudioSource): Boolean = when (source) {
            AudioSource.OFF -> true
            AudioSource.WAVE_PIPE -> true
            AudioSource.SYSTEM_CAPTURE -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        }
    }
}
