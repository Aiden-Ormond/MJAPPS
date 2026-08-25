package com.mikun.mjapps.data

import org.json.JSONObject

/** 矩形区域（像素坐标，原点在视频帧左上角） */
data class SbsRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    override fun toString(): String = "[$x, $y, $w, $h]"
}

/**
 * Alpha Side-by-Side (SbS) 视频配置。
 *
 * 视频帧为左右并排布局：
 *  - [alphaFrame] 区域：灰度 Alpha 遮罩（亮度即透明度）
 *  - [rgbFrame]  区域：彩色 RGB 画面
 * 合成时取 rgbFrame 的颜色、aFrame 对应像素的亮度作为 Alpha，输出 [width] x [height] 的透明视频。
 */
data class SbsVideoConfig(
    val orientation: String,
    val version: Int,
    val path: String,
    val align: Int,
    val hasAudio: Boolean,
    val fps: Int,
    val alphaFrame: SbsRect,
    val rgbFrame: SbsRect,
    val videoWidth: Int,
    val videoHeight: Int,
    val width: Int,
    val height: Int,
) {
    companion object {
        fun fromJson(text: String): SbsVideoConfig {
            val root = JSONObject(text)
            val video = root.optJSONObject("portrait")
                ?: root.getJSONObject(root.keys().next())
            fun rect(key: String): SbsRect {
                val a = video.getJSONArray(key)
                return SbsRect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            }
            return SbsVideoConfig(
                orientation = "portrait",
                version = video.optInt("v", 1),
                path = video.optString("path", "output.mp4"),
                align = video.optInt("align", 8),
                hasAudio = video.optInt("has_audio", 0) == 1,
                fps = video.optInt("f", 30),
                alphaFrame = rect("aFrame"),
                rgbFrame = rect("rgbFrame"),
                videoWidth = video.getInt("videoW"),
                videoHeight = video.getInt("videoH"),
                width = video.getInt("w"),
                height = video.getInt("h"),
            )
        }
    }
}

/** assets 中的一个 SbS 视频条目 */
data class VideoEntry(
    /** assets 内相对目录，例如 "video/video1/3" */
    val dir: String,
    /** 展示名，例如 "video1/3" */
    val title: String,
    val config: SbsVideoConfig,
    /** 自动检测到的真实帧率；未知时为 0f */
    val fps: Float,
)
