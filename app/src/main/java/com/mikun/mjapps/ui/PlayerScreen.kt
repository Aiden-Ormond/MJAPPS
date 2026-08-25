package com.mikun.mjapps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mikun.mjapps.data.VideoEntry
import com.mikun.mjapps.player.AlphaSbsPlayer
import com.mikun.mjapps.player.RenderMode
import com.mikun.mjapps.player.SbsRenderView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    entry: VideoEntry,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(entry) { AlphaSbsPlayer(context, entry.dir, entry.config, entry.fps) }
    val playbackState by player.state.collectAsState()
    var mode by remember { mutableStateOf(RenderMode.COMPOSITE) }
    var looping by remember { mutableStateOf(true) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 页面不可见时暂停，避免后台继续播放声音
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pauseIfNeeded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 视频区：棋盘背景（指示透明）+ GL 合成画面
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Checkerboard(Modifier.fillMaxSize())
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SbsRenderView(ctx, entry.config).apply {
                            this.player = player
                        }
                    },
                    update = { view ->
                        view.mode = mode
                    },
                )
            }

            // 控制区
            ControlPanel(
                state = playbackState,
                mode = mode,
                onModeChange = { mode = it },
                looping = looping,
                onLoopingChange = {
                    looping = it
                    player.setLoopingEnabled(it)
                },
                onTogglePlay = { player.togglePlayPause() },
                onRestart = { player.restart() },
                config = entry.config,
                detectedFps = entry.fps,
            )
        }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier = Modifier) {
    val light = Color(0xFFF2F2F5)
    val dark = Color(0xFFD8D8DE)
    Canvas(modifier = modifier) {
        val cell = 24.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                val color = if ((row + col) % 2 == 0) light else dark
                drawRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(minOf(cell, size.width - x), minOf(cell, size.height - y)),
                )
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

@Composable
private fun ControlPanel(
    state: AlphaSbsPlayer.PlaybackState,
    mode: RenderMode,
    onModeChange: (RenderMode) -> Unit,
    looping: Boolean,
    onLoopingChange: (Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onRestart: () -> Unit,
    config: com.mikun.mjapps.data.SbsVideoConfig,
    detectedFps: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 进度
        val durationUs = state.durationUs.coerceAtLeast(1L)
        val progress = (state.positionUs.toFloat() / durationUs).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatTime(state.positionUs)} / ${formatTime(state.durationUs)}" +
                if (state.finished) " · 已结束" else if (state.playing) "" else " · 已暂停",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // 播放控制 + 模式切换
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                if (state.playing) {
                    PauseIcon(Modifier.size(22.dp))
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = if (state.finished) "重播" else "播放",
                    )
                }
            }
            IconButton(onClick = onRestart) {
                Icon(Icons.Filled.Refresh, contentDescription = "从头播放")
            }
            FilterChip(
                selected = looping,
                onClick = { onLoopingChange(!looping) },
                label = { Text("循环") },
            )
            Spacer(Modifier.width(4.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RenderMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        label = { Text(m.label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 配置信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "config.json (${config.orientation})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                ConfigLine("输出尺寸", "${config.width} × ${config.height}")
                ConfigLine("真实帧率", formatFps(detectedFps))
                ConfigLine("源视频尺寸", "${config.videoWidth} × ${config.videoHeight}")
                ConfigLine("aFrame", config.alphaFrame.toString())
                ConfigLine("rgbFrame", config.rgbFrame.toString())
                ConfigLine("对齐", "${config.align} px")
                ConfigLine("音轨", if (config.hasAudio) "有" else "无")
                ConfigLine("文件", config.path)
            }
        }
    }
}

@Composable
private fun ConfigLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** material-icons-core 没有 Pause 图标，自绘两条竖线 */
@Composable
private fun PauseIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier = modifier) {
        val barW = size.width / 5f
        val gap = size.width / 5f
        drawRect(color, Offset(0f, 0f), Size(barW, size.height))
        drawRect(color, Offset(barW + gap, 0f), Size(barW, size.height))
    }
}

private fun formatTime(us: Long): String {
    val totalSeconds = us / 1_000_000L
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    val tenths = (us / 100_000L) % 10
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}
