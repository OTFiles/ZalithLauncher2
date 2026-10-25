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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "AudioPlayer"
private const val SAMPLE_RATE = 48000
private const val CHANNEL_COUNT = 2

/**
 * 服务端推过来的 AAC 音频播放。
 *
 * 解码成 PCM 后直接写进 AudioTrack（流模式），延迟极低，
 * 低端机也不会有额外负担。
 */
class AudioPlayer {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>(48)
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT)
            val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            created.configure(format, null, null, 0)
            created.start()
            codec = created

            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(SAMPLE_RATE * 2)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
                .also { it.play() }

            running = true
            thread = Thread({ decodeLoop() }, "ZalithAudioPlayer").apply {
                isDaemon = true
                start()
            }
            true
        }.onFailure {
            Log.w(TAG, "Failed to start the audio player", it)
            stop()
        }.getOrDefault(false)
    }

    fun submit(data: ByteArray) {
        if (!running) return
        if (!queue.offer(data)) {
            queue.poll()
            queue.offer(data)
        }
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        val codec = codec ?: return
        val track = track ?: return
        while (running) {
            val data = try {
                queue.poll(30, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            if (data != null) {
                runCatching {
                    val index = codec.dequeueInputBuffer(5_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: return@runCatching
                        buffer.clear()
                        buffer.put(data)
                        codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
                    }
                }
            }
            while (true) {
                val outIndex = try {
                    codec.dequeueOutputBuffer(info, 0)
                } catch (_: IllegalStateException) {
                    return
                }
                if (outIndex < 0) break
                val out = codec.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size)
                    out.get(pcm)
                    runCatching { track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING) }
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    companion object {
        /** 供调试：是否可用 */
        fun isSupported(): Boolean = true

        @Suppress("unused")
        private val audioManagerHint = AudioManager::class.java
    }
}
