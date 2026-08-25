package com.mikun.mjapps

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mikun.mjapps.data.VideoRepository
import com.mikun.mjapps.player.FloatingPrefs
import com.mikun.mjapps.player.FloatingVideoWindow
import com.mikun.mjapps.player.MjImageFloatingWindow
import com.mikun.mjapps.ui.PlayerScreen
import com.mikun.mjapps.ui.VideoListScreen
import com.mikun.mjapps.ui.theme.MJAPPSTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MJAPPSTheme {
                AlphaSbsApp()
            }
        }
    }
}

@Composable
fun AlphaSbsApp() {
    val context = LocalContext.current
    val entries = remember { VideoRepository.scan(context) }

    // 悬浮窗实例（页面销毁时释放）
    val floatingWindow = remember { FloatingVideoWindow(context.applicationContext) }

    // 常驻图片悬浮窗：点击触发随机播放（触发方式由 App 内设置决定）
    val mjWindow = remember {
        MjImageFloatingWindow(context.applicationContext) {
            if (entries.isNotEmpty() && Settings.canDrawOverlays(context)) {
                floatingWindow.show(entries.random())
            }
        }.apply { mode = FloatingPrefs.getMjWindowMode(context) }
    }

    DisposableEffect(floatingWindow, mjWindow) {
        onDispose {
            floatingWindow.release()
            mjWindow.release()
        }
    }

    // 启动即显示常驻悬浮窗（需悬浮窗权限）
    LaunchedEffect(Unit) {
        if (Settings.canDrawOverlays(context)) {
            mjWindow.show()
        }
    }

    // 保存当前选中的条目索引（-1 表示列表页）
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val entry = selectedIndex.takeIf { it in entries.indices }?.let { entries[it] }

    if (entry != null) {
        PlayerScreen(entry = entry, onBack = { selectedIndex = -1 })
    } else {
        VideoListScreen(
            entries = entries,
            mjWindow = mjWindow,
            onOpen = { selectedIndex = entries.indexOf(it) },
            onFloatingRandom = {
                if (entries.isNotEmpty() && Settings.canDrawOverlays(context)) {
                    floatingWindow.show(entries.random())
                }
            },
        )
    }
}
