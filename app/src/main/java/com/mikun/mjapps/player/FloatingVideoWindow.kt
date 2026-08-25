package com.mikun.mjapps.player

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.mikun.mjapps.data.VideoEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.random.Random

/**
 * 悬浮窗视频播放器：在屏幕指定位置显示一个 Alpha SbS 视频，播放一遍后自动消失。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限（调用方负责检查/申请）。
 * 默认位置 [FloatingPosition.TOP_RIGHT]（屏幕顶部右）。
 */
class FloatingVideoWindow(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var renderView: SbsRenderView? = null
    private var player: AlphaSbsPlayer? = null
    private var watcherJob: Job? = null

    fun show(entry: VideoEntry, position: FloatingPosition = FloatingPrefs.getPosition(appContext)) {
        dismiss()
        FloatingWindowState.setShowing(true)

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // 播放一遍（不循环）
        val p = AlphaSbsPlayer(appContext, entry.dir, entry.config, entry.fps).apply {
            looping = false
        }
        player = p

        val view = SbsRenderView(appContext, entry.config).apply { this.player = p }
        renderView = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 尺寸：宽度固定 240dp，高度按视频比例
        val density = appContext.resources.displayMetrics.density
        val width = (240 * density).toInt()
        val height = (width.toFloat() * entry.config.height / entry.config.width).toInt()
        val screenW = appContext.resources.displayMetrics.widthPixels
        val screenH = appContext.resources.displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

        when (position) {
            FloatingPosition.TOP_CENTER -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = (16 * density).toInt()
            }
            FloatingPosition.TOP_LEFT -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = (16 * density).toInt()
                params.y = (16 * density).toInt()
            }
            FloatingPosition.TOP_RIGHT -> {
                params.gravity = Gravity.TOP or Gravity.END
                params.x = (16 * density).toInt()
                params.y = (16 * density).toInt()
            }
            FloatingPosition.RANDOM -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = Random.nextInt(0, max(1, screenW - width))
                params.y = Random.nextInt(0, max(1, screenH - height))
            }
        }

        wm.addView(view, params)

        // 监听播放结束，结束后自动移除
        watcherJob = scope.launch {
            while (true) {
                if (p.state.value.finished) {
                    dismiss()
                    break
                }
                delay(200)
            }
        }
    }

    fun dismiss() {
        watcherJob?.cancel()
        watcherJob = null
        FloatingWindowState.setShowing(false)
        try {
            renderView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        renderView = null
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        windowManager = null
    }

    fun release() {
        dismiss()
        scope.cancel()
    }
}
