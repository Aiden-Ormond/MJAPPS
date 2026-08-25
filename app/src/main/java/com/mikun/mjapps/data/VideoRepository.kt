package com.mikun.mjapps.data

import android.content.Context
import java.io.IOException

/** 扫描 assets/video 下的 Alpha SbS 视频资源 */
object VideoRepository {

    private const val ROOT = "video"
    private const val CONFIG_NAME = "config.json"
    private const val MAX_DEPTH = 5

    fun scan(context: Context): List<VideoEntry> {
        val out = mutableListOf<VideoEntry>()
        walk(context, ROOT, 0, out)
        return out
    }

    private fun walk(context: Context, path: String, depth: Int, out: MutableList<VideoEntry>) {
        if (depth > MAX_DEPTH) return
        val children = try {
            context.assets.list(path)
        } catch (e: IOException) {
            null
        } ?: return
        if (children.isNullOrEmpty()) return

        // 该目录即一个视频条目
        if (children.contains(CONFIG_NAME)) {
            val entry = parseEntry(context, path) ?: return
            out.add(entry)
            return
        }

        children.sorted().forEach { child ->
            walk(context, "$path/$child", depth + 1, out)
        }
    }

    private fun parseEntry(context: Context, dir: String): VideoEntry? {
        return try {
            val text = context.assets.open("$dir/$CONFIG_NAME").bufferedReader().use { it.readText() }
            val config = SbsVideoConfig.fromJson(text)
            // 自动识别真实帧率（不依赖 config.json 的 f 字段）
            val fps = try {
                FrameRateDetector.detect(context, "$dir/${config.path}")
            } catch (e: Exception) {
                0f
            }
            VideoEntry(
                dir = dir,
                title = dir.removePrefix("$ROOT/"),
                config = config,
                fps = fps,
            )
        } catch (e: Exception) {
            null
        }
    }
}
