package com.mikun.mjapps.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 自动识别视频真实帧率。
 *
 * 不信任 config.json 中的 f 字段（可能缺失或不准），而是读取视频轨连续样本的
 * presentationTimeUs，取相邻时间戳差值的中位数来推算真实 fps。
 */
object FrameRateDetector {

    /** 采样帧数（足够覆盖可变帧率场景，取中位数抗噪） */
    private const val SAMPLE_COUNT = 32

    /**
     * 检测 assets 中视频文件的真实帧率。
     *
     * @param assetPath assets 相对路径（含文件名），例如 "video/video1/3/output.mp4"
     * @return 检测到的帧率，未知/失败时返回 0f
     */
    fun detect(context: Context, assetPath: String): Float {
        var afd: AssetFileDescriptor? = null
        val extractor = MediaExtractor()
        try {
            afd = context.assets.openFd(assetPath)
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return 0f
            extractor.selectTrack(track)

            val buf = ByteBuffer.allocate(512 * 1024)
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val times = ArrayList<Long>(SAMPLE_COUNT)
            while (times.size < SAMPLE_COUNT) {
                if (extractor.readSampleData(buf, 0) < 0) break
                times.add(extractor.sampleTime)
                extractor.advance()
            }
            if (times.size < 2) return 0f

            // 相邻样本时间戳差值（微秒），取中位数避免个别异常帧干扰
            val diffs = (1 until times.size)
                .map { times[it] - times[it - 1] }
                .sorted()
            val medianUs = diffs[diffs.size / 2]
            if (medianUs <= 0L) return 0f

            val fps = 1_000_000f / medianUs
            return (fps * 100).roundToInt() / 100f
        } catch (e: Exception) {
            return 0f
        } finally {
            try {
                afd?.close()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
