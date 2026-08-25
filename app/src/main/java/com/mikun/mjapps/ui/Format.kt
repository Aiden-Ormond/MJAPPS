package com.mikun.mjapps.ui

import java.util.Locale

/** 格式化检测到的帧率：90 → "90 fps"，90.5 → "90.5 fps"，未知 → "—" */
internal fun formatFps(fps: Float): String {
    if (fps <= 0f) return "—"
    val s = String.format(Locale.US, "%.1f", fps).trimEnd('0').trimEnd('.')
    return "$s fps"
}
