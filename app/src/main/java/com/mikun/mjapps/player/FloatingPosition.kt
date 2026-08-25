package com.mikun.mjapps.player

import android.content.Context

/** 悬浮窗显示位置 */
enum class FloatingPosition(val label: String) {
    TOP_RIGHT("顶部右"),
    TOP_CENTER("顶部居中"),
    TOP_LEFT("顶部左"),
    RANDOM("随机"),
}

/** 常驻悬浮窗的点击触发方式 */
enum class MjWindowMode(val label: String) {
    DIRECT("直接触发"),
    CODE("暗号"),
}

/** 悬浮窗位置偏好（SharedPreferences 持久化） */
object FloatingPrefs {
    private const val PREF = "floating_prefs"
    private const val KEY_POS = "position"
    private const val KEY_MJ_MODE = "mj_window_mode"

    fun getPosition(context: Context): FloatingPosition {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_POS, null) ?: return FloatingPosition.TOP_RIGHT
        return runCatching { FloatingPosition.valueOf(name) }.getOrDefault(FloatingPosition.TOP_RIGHT)
    }

    fun setPosition(context: Context, pos: FloatingPosition) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POS, pos.name)
            .apply()
    }

    fun getMjWindowMode(context: Context): MjWindowMode {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_MJ_MODE, null) ?: return MjWindowMode.DIRECT
        return runCatching { MjWindowMode.valueOf(name) }.getOrDefault(MjWindowMode.DIRECT)
    }

    fun setMjWindowMode(context: Context, mode: MjWindowMode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MJ_MODE, mode.name)
            .apply()
    }
}
