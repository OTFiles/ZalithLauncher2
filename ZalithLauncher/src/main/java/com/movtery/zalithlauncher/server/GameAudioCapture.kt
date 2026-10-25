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

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GameAudioCapture"
private const val SAMPLE_RATE = 48000
private const val CHANNEL_COUNT = 2
private const val BITRATE = 96_000
private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

/**
 * 游戏音频采集与编码。
 *
 * 用 [AudioPlaybackCaptureConfiguration]（Android 10+）把自家应用的播放声音抓出来，
 * 再编码成 AAC 发给客户端。这样客户端能听到游戏声音，而不是只有画面。
 *
 * 需要 MediaProjection 授权；没有授权时静默降级为"无声音"。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class GameAudioCapture(
    private val mediaProjection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var recordThread: Thread? = null
    private var drainThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true
        return runCatching {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            @SuppressLint("MissingPermission")
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(SAMPLE_RATE)
                )
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized, audio streaming disabled")
                runCatching { record.release() }
                return false
            }

            val codecFormat = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MIME_AAC)
            codec.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            audioRecord = record
            encoder = codec
            running.set(true)
            record.startRecording()
            startDrain(codec)
            recordThread = Thread({ recordLoop(record, codec) }, "ZalithAudioRecord").apply {
                isDaemon = true
                start()
            }
            true
        }.onFailure {
            Log.w(TAG, "Failed to start audio capture", it)
            stop()
        }.getOrDefault(false)
    }

    private fun recordLoop(record: AudioRecord, codec: MediaCodec) {
        val buffer = ByteArray(16 * 1024)
        while (running.get()) {
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) {
                if (read < 0) Log.w(TAG, "AudioRecord.read error: $read")
                continue
            }
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val input = codec.getInputBuffer(index) ?: continue
            input.clear()
            input.put(buffer, 0, read)
            codec.queueInputBuffer(index, 0, read, System.nanoTime() / 1000, 0)
        }
    }

    private fun startDrain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        drainThread = Thread({
            while (running.get()) {
                try {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val out: ByteBuffer? = codec.getOutputBuffer(index)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            out.get(data)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                onFrame(data)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                } catch (_: IllegalStateException) {
                    return@Thread
                } catch (e: Throwable) {
                    Log.w(TAG, "Audio drain failed", e)
                    return@Thread
                }
            }
        }, "ZalithAudioDrain").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordThread?.interrupt()
        recordThread = null
        drainThread?.interrupt()
        drainThread = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
    }
}
