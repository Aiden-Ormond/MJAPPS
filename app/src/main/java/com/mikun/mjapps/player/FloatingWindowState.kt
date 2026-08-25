package com.mikun.mjapps.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局悬浮窗播放状态。
 *
 * [FloatingVideoWindow] 在显示/移除悬浮窗时更新此状态，
 * 列表页的状态卡片据此显示「工作中 / 未工作」。
 */
object FloatingWindowState {
    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing

    fun setShowing(value: Boolean) {
        _showing.value = value
    }
}
