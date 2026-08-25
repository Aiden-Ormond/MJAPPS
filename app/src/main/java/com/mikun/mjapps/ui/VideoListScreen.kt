package com.mikun.mjapps.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mikun.mjapps.data.VideoEntry
import com.mikun.mjapps.player.FloatingPosition
import com.mikun.mjapps.player.FloatingPrefs
import com.mikun.mjapps.player.MjImageFloatingWindow
import com.mikun.mjapps.player.MjWindowMode
import androidx.compose.runtime.collectAsState

private val WorkingGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(
    entries: List<VideoEntry>,
    mjWindow: MjImageFloatingWindow,
    onOpen: (VideoEntry) -> Unit,
    onFloatingRandom: () -> Unit,
) {
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var position by remember { mutableStateOf(FloatingPrefs.getPosition(context)) }
    var triggerMode by remember { mutableStateOf(FloatingPrefs.getMjWindowMode(context)) }
    var grantedPrev by remember { mutableStateOf(canOverlay) }
    val mjVisible by mjWindow.visible.collectAsState()

    // 从系统设置（悬浮窗权限）返回后刷新状态；若权限由「未授权」变为「已授权」，立即显示常驻悬浮窗
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = Settings.canDrawOverlays(context)
                canOverlay = now
                if (now && !grantedPrev) {
                    mjWindow.show()
                }
                grantedPrev = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 工作状态 = 悬浮窗权限是否已授权
    val working = canOverlay

    Scaffold(
        topBar = { TopAppBar(title = { Text("MJ APP") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(working = working) }
            item {
                MjWindowCard(
                    visible = mjVisible,
                    canOverlay = canOverlay,
                    mode = triggerMode,
                    onToggle = {
                        if (mjVisible) mjWindow.hide() else mjWindow.show()
                    },
                    onRequestPermission = { requestOverlayPermission(context) },
                    onModeChange = {
                        triggerMode = it
                        FloatingPrefs.setMjWindowMode(context, it)
                        mjWindow.mode = it
                    },
                )
            }
            item {
                RandomPlayCard(
                    enabled = canOverlay && entries.isNotEmpty(),
                    position = position,
                    onPlay = onFloatingRandom,
                    onPositionChange = {
                        position = it
                        FloatingPrefs.setPosition(context, it)
                    },
                )
            }
            item {
                DeveloperCard(onOpen = { openBrowser(context, "https://mikun.dpdns.org") })
            }
            item {
                GithubCard(onOpen = { openBrowser(context, "https://github.com/MI-KUNs/MJAPPS") })
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        "未在 assets/video 下找到视频资源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                items(entries, key = { it.dir }) { entry ->
                    VideoCard(entry = entry, onClick = { onOpen(entry) })
                }
            }
        }
    }
}

@Composable
private fun StatusCard(working: Boolean) {
    val statusText = if (working) "工作中" else "未工作"
    val statusColor = if (working) WorkingGreen else MaterialTheme.colorScheme.error
    val subText = if (working) "悬浮窗权限已授权，伙计去玩吧" else "悬浮窗权限未授权"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    statusText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusIcon(working = working, modifier = Modifier.size(56.dp))
        }
    }
}

/** 圆圈 + 对号（工作中，绿）/ 圆圈 + 叉号（未工作，红） */
@Composable
private fun StatusIcon(working: Boolean, modifier: Modifier = Modifier) {
    val color = if (working) WorkingGreen else MaterialTheme.colorScheme.error
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f * 0.82f
        val strokeW = size.minDimension * 0.09f

        // 圆圈
        drawCircle(
            color = color,
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = strokeW),
        )

        if (working) {
            // 对号
            val path = Path().apply {
                moveTo(cx - r * 0.45f, cy - r * 0.02f)
                lineTo(cx - r * 0.12f, cy + r * 0.32f)
                lineTo(cx + r * 0.5f, cy - r * 0.38f)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        } else {
            // 叉号
            drawLine(
                color = color,
                start = Offset(cx - r * 0.35f, cy - r * 0.35f),
                end = Offset(cx + r * 0.35f, cy + r * 0.35f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(cx + r * 0.35f, cy - r * 0.35f),
                end = Offset(cx - r * 0.35f, cy + r * 0.35f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 常驻悬浮窗 (MJ)：权限 + 触发方式 + 显隐，整合为一张卡 */
@Composable
private fun MjWindowCard(
    visible: Boolean,
    canOverlay: Boolean,
    mode: MjWindowMode,
    onToggle: () -> Unit,
    onRequestPermission: () -> Unit,
    onModeChange: (MjWindowMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行 + 显隐操作
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = if (canOverlay) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
                Spacer(Modifier.width(12.dp))
                Text("常驻悬浮窗 (MJ)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onToggle,
                    enabled = canOverlay,
                ) {
                    Text(if (visible) "隐藏" else "显示")
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // 悬浮窗权限
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("悬浮窗权限", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                if (canOverlay) {
                    Text("已授权", color = WorkingGreen)
                } else {
                    TextButton(onClick = onRequestPermission) { Text("去授权") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "授权后Q版蜘蛛侠常驻屏幕边缘，可拖动并自动吸附左右两边",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // 触发方式
            Text("触发方式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MjWindowMode.entries.forEach { m ->
                    FilterChip(
                        selected = m == mode,
                        onClick = { onModeChange(m) },
                        label = { Text(m.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (mode == MjWindowMode.DIRECT) "轻点悬浮图标即随机播放" else "轻点悬浮图标弹出暗号键盘，输入正确暗号后播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 随机悬浮播放：播放按钮 + 显示位置，整合为一张卡 */
@Composable
private fun RandomPlayCard(
    enabled: Boolean,
    position: FloatingPosition,
    onPlay: () -> Unit,
    onPositionChange: (FloatingPosition) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPlay),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
                Spacer(Modifier.width(12.dp))
                Text("随机悬浮播放", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) "随机选一个视频在悬浮窗播放一遍" else "需要先授权悬浮窗权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text("显示位置", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingPosition.entries.forEach { p ->
                    FilterChip(
                        selected = p == position,
                        onClick = { onPositionChange(p) },
                        label = { Text(p.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoCard(entry: VideoEntry, onClick: () -> Unit) {
    val c = entry.config
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                if (c.hasAudio) {
                    Text("♪", color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "输出 ${c.width}×${c.height} · ${formatFps(entry.fps)}${if (c.hasAudio) " · 含音频" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "源帧 ${c.videoWidth}×${c.videoHeight} · aFrame ${c.alphaFrame} · rgbFrame ${c.rgbFrame}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun requestOverlayPermission(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}

@Composable
private fun DeveloperCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("开发者网页", style = MaterialTheme.typography.titleMedium)
                Text(
                    "BY MIKUN mikun.dpdns.org",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "打开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GithubCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GitHub 仓库", style = MaterialTheme.typography.titleMedium)
                Text(
                    "github.com/MI-KUNs/MJAPPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "打开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun openBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
