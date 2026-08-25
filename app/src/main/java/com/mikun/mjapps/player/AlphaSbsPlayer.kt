package com.mikun.mjapps.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import com.mikun.mjapps.data.SbsVideoConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CyclicBarrier
import kotlin.math.max

/**
 * Alpha SbS 视频播放器。
 *
 * 视频：MediaCodec 解码 -> GL Surface（由 [SbsRenderView] 提供）。
 * 音频：MediaCodec 解码 -> AudioTrack。
 * 同步：视频帧按播放时钟等待对应的 presentationTime；音频由 AudioTrack 自然速率消耗。
 * 支持暂停/继续、循环、从头重播。
 */
class AlphaSbsPlayer(
    private val context: Context,
    /** assets 内相对目录，例如 "video/video1/3" */
    private val assetDir: String,
    val config: SbsVideoConfig,
    /** 播放帧率（自动检测到的真实帧率），用于固定节奏调度；<=0 时回退 30fps */
    private val fps: Float = 30f,
) {

    data class PlaybackState(
        val positionUs: Long = 0L,
        val durationUs: Long = 0L,
        val playing: Boolean = false,
        val finished: Boolean = false,
    )

    private val lock = Any()
    private var started = false
    private var released = false
    private var paused = false
    private var finished = false
    private var restartRequested = false

    /** 是否循环播放 */
    @Volatile
    var looping: Boolean = true

    private val clock = PlayClock()

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    @Volatile
    private var durationUs = 0L

    @Volatile
    private var audioTrack: AudioTrack? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var loopBarrier: CyclicBarrier? = null
    private var lastPublishedPos = -1L

    /**
     * 由 GL 渲染线程在 Surface 就绪后调用，启动解码线程（幂等）。
     */
    fun start(surface: Surface) {
        synchronized(lock) {
            if (started || released) return
            started = true
            val hasAudio = probeAudioTrack() >= 0
            loopBarrier = CyclicBarrier(if (hasAudio) 2 else 1)
            videoThread = Thread({ videoLoop(surface) }, "sbs-video").apply { start() }
            if (hasAudio) {
                audioThread = Thread({ audioLoop() }, "sbs-audio").apply { start() }
            }
            publishStateLocked()
        }
    }

    /** 播放/暂停切换；已结束时从头重播 */
    fun togglePlayPause() {
        synchronized(lock) {
            if (!started || released) return
            if (finished) {
                restartLocked()
            } else {
                paused = !paused
                if (paused) {
                    clock.pause()
                    audioTrack?.pause()
                } else {
                    clock.resume()
                    audioTrack?.play()
                }
            }
            publishStateLocked()
        }
    }

    /** 生命周期 ON_STOP 时外部暂停（可继续） */
    fun pauseIfNeeded() {
        synchronized(lock) {
            if (!started || released || paused || finished) return
            paused = true
            clock.pause()
            audioTrack?.pause()
            publishStateLocked()
        }
    }

    /** 从头重播 */
    fun restart() {
        synchronized(lock) {
            if (!started || released) return
            restartLocked()
            publishStateLocked()
        }
    }

    fun setLoopingEnabled(value: Boolean) {
        looping = value
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            notifyAllLocked()
        }
        try {
            videoThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        try {
            audioThread?.join(1000)
        } catch (_: InterruptedException) {
        }
        audioTrack = null
    }

    private fun restartLocked() {
        finished = false
        paused = false
        restartRequested = true
        clock.start()
        notifyAllLocked()
    }

    // ------------------------------------------------------------------
    // 视频线程
    // ------------------------------------------------------------------

    private fun videoLoop(surface: Surface) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = openExtractor()
            val trackIndex = selectTrack(extractor, "video/") ?: return
            val format = extractor.getTrackFormat(trackIndex)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            codec.start()
            publishState()

            // 固定帧率节奏调度（不依赖样本 pts 的细微差异）
            val frameDurationUs = if (fps > 0f) (1_000_000f / fps).toLong() else 33_333L
            var frameIndex = 0L
            clock.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!released) {
                // 外部请求从头重播
                if (restartRequested) {
                    consumeRestart(extractor, codec)
                    inputDone = false
                    outputDone = false
                    frameIndex = 0L
                    continue
                }

                if (outputDone) {
                    if (looping) {
                        loopBarrier?.await()
                        clock.start()
                        seekAndFlush(extractor, codec)
                        inputDone = false
                        outputDone = false
                        frameIndex = 0L
                        continue
                    } else {
                        val doRestart = finishAndAwaitRestart()
                        if (!doRestart) break
                        clock.start()
                        seekAndFlush(extractor, codec)
                        inputDone = false
                        outputDone = false
                        frameIndex = 0L
                        continue
                    }
                }

                // 喂输入
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 取输出
                val outIdx = codec.dequeueOutputBuffer(info, if (inputDone) 10_000L else 0L)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIdx, false)
                        outputDone = true
                    } else {
                        waitUntil(frameIndex * frameDurationUs)
                        if (released) break
                        codec.releaseOutputBuffer(outIdx, true)
                        onFrameRendered(frameIndex * frameDurationUs)
                        frameIndex++
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED / TRY_AGAIN_LATER 直接继续
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            android.util.Log.e(TAG, "video loop error", e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor?.release()
        }
    }

    private fun consumeRestart(extractor: MediaExtractor, codec: MediaCodec) {
        synchronized(lock) {
            restartRequested = false
        }
        clock.start()
        seekAndFlush(extractor, codec)
    }

    private fun seekAndFlush(extractor: MediaExtractor, codec: MediaCodec) {
        codec.flush()
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /**
     * 非循环模式播放结束：冻结时钟并等待重播/释放。
     * 返回 true 表示需要从头重播，false 表示已释放。
     */
    private fun finishAndAwaitRestart(): Boolean {
        synchronized(lock) {
            if (!finished) {
                finished = true
                paused = true
                clock.pause()
            }
            publishStateLocked()
            while (!released && !restartRequested) {
                waitLocked(500L)
            }
            if (released) return false
            restartRequested = false
            finished = false
            paused = false
            publishStateLocked()
            return true
        }
    }

    private fun waitUntil(targetUs: Long) {
        while (!released) {
            if (paused) {
                synchronized(lock) {
                    while (paused && !released) waitLocked(500L)
                }
                if (released) return
            }
            val deltaMs = (targetUs - clock.now()) / 1000L
            when {
                deltaMs <= 0L -> return
                deltaMs <= 3L -> SystemClock.sleep(deltaMs)
                else -> SystemClock.sleep(deltaMs.coerceAtMost(20L))
            }
        }
    }

    private fun onFrameRendered(ptsUs: Long) {
        if (ptsUs - lastPublishedPos >= 100_000L || ptsUs < lastPublishedPos) {
            lastPublishedPos = ptsUs
            _state.value = _state.value.copy(positionUs = ptsUs, durationUs = durationUs)
        }
    }

    // ------------------------------------------------------------------
    // 音频线程
    // ------------------------------------------------------------------

    private fun audioLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            extractor = openExtractor()
            val trackIndex = selectTrack(extractor, "audio/") ?: return
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var pcmFloat = false
            var t = buildAudioTrack(sampleRate, channels, pcmFloat)
            track = t
            t.play()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!released) {
                if (restartRequested) {
                    consumeRestart(extractor, codec)
                    t.flush()
                    t.play()
                    inputDone = false
                    outputDone = false
                    continue
                }

                if (outputDone) {
                    if (looping) {
                        loopBarrier?.await()
                        seekAndFlush(extractor, codec)
                        t.flush()
                        t.play()
                        inputDone = false
                        outputDone = false
                        continue
                    } else {
                        val doRestart = finishAndAwaitRestart()
                        if (!doRestart) break
                        seekAndFlush(extractor, codec)
                        t.flush()
                        t.play()
                        inputDone = false
                        outputDone = false
                        continue
                    }
                }

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, if (inputDone) 10_000L else 0L)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(outIdx, false)
                        outputDone = true
                    } else {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            if (waitForPlay() && !released) {
                                writeNonBlocking(t, out)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    val enc = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (enc == AudioFormat.ENCODING_PCM_FLOAT && !pcmFloat) {
                        // 解码器输出 float PCM，重建 AudioTrack
                        pcmFloat = true
                        t.pause()
                        t.flush()
                        t.release()
                        t = buildAudioTrack(sampleRate, channels, pcmFloat)
                        track = t
                        t.play()
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            android.util.Log.e(TAG, "audio loop error", e)
        } finally {
            try {
                track?.pause()
                track?.flush()
            } catch (_: Exception) {
            }
            track?.release()
            if (audioTrack === track) audioTrack = null
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor?.release()
        }
    }

    /** 等待非暂停状态；返回 false 表示已释放 */
    private fun waitForPlay(): Boolean {
        synchronized(lock) {
            while (paused && !released && !restartRequested) {
                waitLocked(1000L)
            }
            return !released
        }
    }

    private fun writeNonBlocking(track: AudioTrack, buf: java.nio.ByteBuffer) {
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        var offset = 0
        while (offset < bytes.size && !released) {
            synchronized(lock) {
                while (paused && !released) waitLocked(500L)
            }
            if (released) return
            val wrote = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (wrote < 0) return
            if (wrote == 0) SystemClock.sleep(10L)
            offset += wrote
        }
    }

    private fun buildAudioTrack(sampleRate: Int, channels: Int, pcmFloat: Boolean): AudioTrack {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, channelMask,
            if (pcmFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        )
        val encoding = if (pcmFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 2, 8192))
            .build()
    }

    // ------------------------------------------------------------------
    // 公共工具
    // ------------------------------------------------------------------

    private fun openExtractor(): MediaExtractor {
        val afd = context.assets.openFd("$assetDir/${config.path}")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        } finally {
            afd.close()
        }
        return extractor
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                extractor.selectTrack(i)
                return i
            }
        }
        return null
    }

    private fun probeAudioTrack(): Int {
        return try {
            val extractor = openExtractor()
            try {
                (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: -1
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun publishState() {
        synchronized(lock) {
            publishStateLocked()
        }
    }

    private fun publishStateLocked() {
        _state.value = PlaybackState(
            positionUs = if (finished) durationUs else max(lastPublishedPos, 0L),
            durationUs = durationUs,
            playing = started && !paused && !finished && !released,
            finished = finished,
        )
    }

    /** 播放时钟：媒体时间（微秒），支持暂停冻结 */
    private class PlayClock {
        private var baseMediaUs = 0L
        private var baseElapsedUs = 0L
        private var pausedMediaUs = -1L

        fun start() {
            synchronized(this) {
                baseMediaUs = 0L
                baseElapsedUs = elapsedNow()
                pausedMediaUs = -1L
            }
        }

        fun pause() {
            synchronized(this) {
                if (pausedMediaUs < 0L) pausedMediaUs = nowLocked()
            }
        }

        fun resume() {
            synchronized(this) {
                if (pausedMediaUs >= 0L) {
                    baseMediaUs = pausedMediaUs
                    baseElapsedUs = elapsedNow()
                    pausedMediaUs = -1L
                }
            }
        }

        fun now(): Long = synchronized(this) {
            if (pausedMediaUs >= 0L) pausedMediaUs else nowLocked()
        }

        private fun nowLocked() = baseMediaUs + (elapsedNow() - baseElapsedUs)

        private fun elapsedNow() = SystemClock.elapsedRealtimeNanos() / 1000L
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun notifyAllLocked() {
        (lock as java.lang.Object).notifyAll()
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitLocked(timeoutMs: Long) {
        (lock as java.lang.Object).wait(timeoutMs)
    }

    companion object {
        private const val TAG = "AlphaSbsPlayer"
    }
}
