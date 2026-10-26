package com.shiliu.stream.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.shiliu.stream.StreamController
import com.shiliu.stream.camera.VideoOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StreamScreen(controller: StreamController) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var showSheet by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) controller.onCameraPermissionGranted()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
        // 前台服务的常驻通知需要通知权限；未授权也能推流，只是通知不可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(controller.streaming, controller.startElapsed) {
        if (controller.streaming) {
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - controller.startElapsed) / 1000L
                delay(1000)
            }
        } else {
            elapsed = 0L
        }
    }

    LaunchedEffect(controller.streaming) {
        while (controller.streaming) {
            controller.refreshStats()
            delay(1000)
        }
    }

    BackHandler(enabled = showSheet) { showSheet = false }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Tok.BgTop, Tok.Bg, Tok.BgDeep)))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AdaptiveLayout(
                landscape = landscape,
                modifier = if (landscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxHeight()
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                },
                header = { Header(controller) },
                viewport = {
                    Viewport(
                        controller = controller,
                        permissionGranted = permissionGranted,
                        elapsed = elapsed,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = if (landscape) {
                            Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        } else {
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp)
                        }
                    )
                },
                dock = {
                    Dock(
                        controller = controller,
                        onOpenSheet = { showSheet = true },
                        modifier = if (landscape) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    )
                }
            )
        }

        AnimatedVisibility(
            visible = showSheet,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSheet = false }
            )
        }

        AnimatedVisibility(
            visible = showSheet,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(280)) { it },
            exit = slideOutVertically(tween(240)) { it }
        ) {
            ResolutionSheet(controller) { option ->
                controller.selectSize(option)
                showSheet = false
            }
        }

        ToastHost(controller, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * 竖屏时上下堆叠，横屏时预览在左、控制面板在右。
 * 两种方向共用同一套子组合，避免旋转时 TextureView 被重建导致预览 Surface 失效。
 */
@Composable
private fun AdaptiveLayout(
    landscape: Boolean,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    viewport: @Composable () -> Unit,
    dock: @Composable () -> Unit
) {
    val dockWidth = 330.dp
    Layout(
        modifier = modifier,
        content = {
            header()
            viewport()
            dock()
        }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val headerMeasurable = measurables[0]
        val viewportMeasurable = measurables[1]
        val dockMeasurable = measurables[2]

        if (landscape) {
            val right = minOf(dockWidth.roundToPx(), width / 2)
            val headerPlaceable = headerMeasurable.measure(Constraints(maxWidth = right))
            val dockPlaceable = dockMeasurable.measure(
                Constraints(
                    minWidth = right,
                    maxWidth = right,
                    maxHeight = (height - headerPlaceable.height).coerceAtLeast(0)
                )
            )
            val left = width - right
            val viewportPlaceable = viewportMeasurable.measure(
                Constraints(minWidth = left, maxWidth = left, minHeight = height, maxHeight = height)
            )
            layout(width, height) {
                viewportPlaceable.place(0, 0)
                headerPlaceable.place(left, 0)
                dockPlaceable.place(left, headerPlaceable.height)
            }
        } else {
            val headerPlaceable = headerMeasurable.measure(Constraints(maxWidth = width))
            val dockPlaceable = dockMeasurable.measure(
                Constraints(
                    maxWidth = width,
                    maxHeight = (height - headerPlaceable.height).coerceAtLeast(0)
                )
            )
            val viewportHeight =
                (height - headerPlaceable.height - dockPlaceable.height).coerceAtLeast(0)
            val viewportPlaceable = viewportMeasurable.measure(
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    minHeight = viewportHeight,
                    maxHeight = viewportHeight
                )
            )
            layout(width, height) {
                headerPlaceable.place(0, 0)
                viewportPlaceable.place(0, headerPlaceable.height)
                dockPlaceable.place(0, height - dockPlaceable.height)
            }
        }
    }
}

@Composable
private fun Header(controller: StreamController) {
    val lensCount = controller.lenses.size
    val countLabel = when {
        lensCount <= 0 -> "无镜头"
        lensCount == 1 -> "单摄"
        else -> "${lensCount}摄"
    }
    val statusLabel = when {
        controller.streaming -> "推流中"
        controller.cameraReady -> "已连接"
        else -> "连接中"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(Tok.AccentSoft, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            CameraGlyph(Tok.Accent, 16.dp)
        }
        Spacer(Modifier.width(9.dp))
        Text("视流", color = Tok.Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .border(1.dp, Tok.Border, RoundedCornerShape(999.dp))
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (controller.cameraReady || controller.streaming) Tok.Accent else Tok.Muted,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$countLabel · $statusLabel",
                fontFamily = Tok.Mono,
                fontSize = 11.sp,
                color = Tok.Muted
            )
        }
    }
}

@Composable
private fun Viewport(
    controller: StreamController,
    permissionGranted: Boolean,
    elapsed: Long,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Tok.RadiusLarge))
            .background(Color(0xFF010304))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(Tok.RadiusLarge))
    ) {
        if (permissionGranted) {
            PreviewTexture(controller)
        } else {
            PermissionPlaceholder(onRequestPermission)
        }

        if (permissionGranted && !controller.cameraReady) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val error = controller.cameraError
                if (error != null) {
                    Text(error, color = Tok.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    SmallButton("重试") { controller.retryCamera() }
                } else {
                    CircularProgressIndicator(
                        Modifier.size(22.dp),
                        color = Tok.Accent,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("正在打开镜头…", color = Tok.Muted, fontSize = 12.sp)
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            HudBadge(controller.selectedLens?.displayName ?: "—")
            HudBadge(controller.selectedSize?.text ?: "—")
        }

        if (controller.streaming) {
            LivePill(elapsed, Modifier.align(Alignment.TopCenter).padding(top = 44.dp))
        }
    }
}

@Composable
private fun PreviewTexture(controller: StreamController) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        controller.onPreviewSurface(Surface(texture), width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        controller.onPreviewLayoutChanged(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(
                        texture: android.graphics.SurfaceTexture
                    ): Boolean {
                        controller.onPreviewSurface(null, 0, 0)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun PermissionPlaceholder(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CameraGlyph(Tok.Accent, 34.dp)
        Spacer(Modifier.height(14.dp))
        Text("需要相机权限", color = Tok.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "授予权限后才能预览画面并建立 RTSP 推流服务",
            color = Tok.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        SmallButton("授予相机权限", onRequest)
    }
}

@Composable
private fun HudBadge(text: String) {
    Text(
        text,
        color = Tok.Fg,
        fontFamily = Tok.Mono,
        fontSize = 11.sp,
        modifier = Modifier
            .background(Color(0x8C0D1319), RoundedCornerShape(999.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun LivePill(elapsed: Long, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDot"
    )
    Row(
        modifier
            .background(Color(0x73000000), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(Tok.Danger.copy(alpha = alpha), CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "LIVE",
            color = Tok.Fg,
            fontFamily = Tok.Mono,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            formatElapsed(elapsed),
            color = Tok.Fg,
            fontFamily = Tok.Mono,
            fontSize = 11.sp
        )
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

@Composable
private fun Dock(
    controller: StreamController,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (controller.streaming) {
            LiveControls(controller)
        } else {
            PreControls(controller, onOpenSheet)
        }
    }
}

@Composable
private fun PreControls(controller: StreamController, onOpenSheet: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CtlCard(
                modifier = Modifier.weight(1f),
                label = "当前镜头",
                value = controller.selectedLens?.facingText ?: "—",
                hint = if (controller.hasMultipleLenses) "切换 ↺" else "单镜头",
                enabled = controller.hasMultipleLenses && !controller.connecting,
                onClick = controller::cycleLens,
                icon = { CameraGlyph(Tok.Muted, 14.dp) }
            )
            CtlCard(
                modifier = Modifier.weight(1f),
                label = "推流分辨率",
                value = controller.selectedSize?.text ?: "—",
                hint = "⌄",
                enabled = controller.selectedSize != null && !controller.connecting,
                onClick = onOpenSheet,
                icon = { DisplayGlyph(Tok.Muted, 14.dp) }
            )
        }

        PasswordField(controller)
        StartButton(controller)
    }
}

@Composable
private fun CtlCard(
    modifier: Modifier,
    label: String,
    value: String,
    hint: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Tok.Radius))
            .background(Tok.Surface)
            .border(1.dp, Tok.Border, RoundedCornerShape(Tok.Radius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(label, color = Tok.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value,
                color = Tok.Fg,
                fontFamily = Tok.Mono,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(hint, color = Tok.Muted, fontFamily = Tok.Mono, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PasswordField(controller: StreamController) {
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("推流密码（可选，留空则公开）", color = Tok.Muted, fontSize = 11.sp)
        Box(Modifier.fillMaxWidth()) {
            BasicTextField(
                value = controller.password,
                onValueChange = { if (it.length <= 64) controller.updatePassword(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = TextStyle(color = Tok.Fg, fontFamily = Tok.Mono, fontSize = 14.sp),
                cursorBrush = SolidColor(Tok.Accent),
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Tok.Surface, RoundedCornerShape(Tok.Radius))
                            .border(
                                1.dp,
                                if (focused) Tok.Accent else Tok.Border,
                                RoundedCornerShape(Tok.Radius)
                            )
                            .padding(start = 14.dp, end = 44.dp, top = 13.dp, bottom = 13.dp)
                    ) {
                        if (controller.password.isEmpty()) {
                            Text(
                                "输入自定义密码",
                                color = Tok.Muted,
                                fontFamily = Tok.Mono,
                                fontSize = 14.sp
                            )
                        }
                        inner()
                    }
                }
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { visible = !visible },
                contentAlignment = Alignment.Center
            ) {
                EyeGlyph(if (visible) Tok.Fg else Tok.Muted, 18.dp)
            }
        }
        Text("密码会写入 RTSP 链接（Basic 鉴权）", color = Tok.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun StartButton(controller: StreamController) {
    val enabled = !controller.connecting &&
        controller.selectedLens != null &&
        controller.selectedSize != null
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Tok.Radius))
            .background(if (controller.connecting) Tok.Accent.copy(alpha = 0.7f) else Tok.Accent)
            .clickable(enabled = enabled, onClick = controller::start),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (controller.connecting) {
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    color = Tok.OnAccent,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (controller.connecting) "正在建立推流…" else "开始推流",
                color = Tok.OnAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LiveControls(controller: StreamController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SignalCard(
            protocolTag = "RTSP",
            label = "拉流地址",
            url = controller.displayRtspUrl,
            copyText = controller.rtspUrl,
            onCopied = { controller.notify("已复制 RTSP 地址") }
        )
        SignalCard(
            protocolTag = "RTP",
            label = "UDP 广播（无握手）",
            url = controller.displayRtpUrl,
            copyText = controller.rtpUrl,
            onCopied = { controller.notify("已复制 RTP 广播地址") }
        )
        AuthNote(
            hasPassword = controller.password.isNotEmpty(),
            clientCount = controller.clientCount
        )
        StatsCard(
            fps = controller.statFps,
            bitrate = controller.statBitrate,
            rtspClients = controller.clientCount,
            rtpClients = controller.rtpClientCount,
            dropped = controller.droppedPackets
        )
        DangerButton("停止推流") { controller.stop() }
    }
}

@Composable
private fun SignalCard(
    protocolTag: String,
    label: String,
    url: String,
    copyText: String,
    onCopied: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tok.Surface, RoundedCornerShape(Tok.Radius))
            .border(1.dp, Tok.Border, RoundedCornerShape(Tok.Radius))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    protocolTag,
                    color = Tok.Accent,
                    fontFamily = Tok.Mono,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .border(1.dp, Tok.Border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    color = Tok.Muted,
                    fontFamily = Tok.Mono,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp
                )
            }
            CopyButton(copyText, onCopied)
        }
        Text(
            url,
            color = Tok.Fg,
            fontFamily = Tok.Mono,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun CopyButton(text: String, onCopied: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Tok.Surface2)
            .border(
                1.dp,
                if (copied) Tok.Accent else Tok.Border,
                RoundedCornerShape(8.dp)
            )
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
                onCopied()
                scope.launch {
                    delay(1600)
                    copied = false
                }
            }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CopyGlyph(if (copied) Tok.Accent else Tok.Fg, 13.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            if (copied) "已复制" else "复制",
            color = if (copied) Tok.Accent else Tok.Fg,
            fontFamily = Tok.Mono,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AuthNote(hasPassword: Boolean, clientCount: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Tok.AccentSoft, RoundedCornerShape(Tok.Radius))
            .border(
                1.dp,
                Tok.Accent.copy(alpha = 0.30f),
                RoundedCornerShape(Tok.Radius)
            )
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LockGlyph(Tok.Accent, 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (hasPassword) "鉴权已启用 · 密码已设置" else "公开推流 · 未设置密码",
            color = Tok.Fg,
            fontSize = 12.sp
        )
        Spacer(Modifier.weight(1f))
        if (clientCount > 0) {
            Text(
                "$clientCount 位观看",
                color = Tok.Muted,
                fontFamily = Tok.Mono,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun StatsCard(
    fps: Int,
    bitrate: Long,
    rtspClients: Int,
    rtpClients: Int,
    dropped: Long
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Tok.Surface, RoundedCornerShape(Tok.Radius))
            .border(1.dp, Tok.Border, RoundedCornerShape(Tok.Radius))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "运行统计",
                color = Tok.Muted,
                fontFamily = Tok.Mono,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "1s 窗口",
                color = Tok.Muted,
                fontFamily = Tok.Mono,
                fontSize = 10.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatItem("帧率 FPS", "$fps", Modifier.weight(1f))
            StatItem("码率 Mbps", String.format("%.1f", bitrate / 1_000_000.0), Modifier.weight(1f))
            StatItem("客户端 RTSP/RTP", "$rtspClients/$rtpClients", Modifier.weight(1f))
            StatItem("丢弃包", "$dropped", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            color = Tok.Fg,
            fontFamily = Tok.Mono,
            fontSize = 14.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = Tok.Muted,
            fontFamily = Tok.Mono,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Tok.Radius))
            .background(Tok.Danger)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Tok.OnDanger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tok.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Tok.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ResolutionSheet(controller: StreamController, onSelect: (VideoOption) -> Unit) {
    val lens = controller.selectedLens
    val sizes = lens?.sizes.orEmpty()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .background(
                Tok.Surface,
                RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            )
            .border(
                1.dp,
                Tok.Border,
                RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            )
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .background(Tok.Border, RoundedCornerShape(999.dp))
        )
        Spacer(Modifier.height(14.dp))
        Text("选择推流分辨率", color = Tok.Fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "当前镜头：${lens?.displayName ?: "—"} · 列表来自摄像头实际支持能力",
            color = Tok.Muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = if (landscape) 170.dp else 340.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (sizes.isEmpty()) {
                Text("该镜头没有可用的编码分辨率", color = Tok.Muted, fontSize = 12.sp)
            } else {
                sizes.forEach { option ->
                    ResolutionItem(
                        option = option,
                        selected = option == controller.selectedSize
                    ) { onSelect(option) }
                }
            }
        }
    }
}

@Composable
private fun ResolutionItem(option: VideoOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tok.Radius))
            .background(if (selected) Tok.AccentSoft else Tok.Surface2)
            .border(
                1.dp,
                if (selected) Tok.Accent else Tok.Border,
                RoundedCornerShape(Tok.Radius)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${option.width}×${option.height} · ${option.label}",
                color = Tok.Fg,
                fontFamily = Tok.Mono,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${option.fps} fps",
                color = Tok.Muted,
                fontFamily = Tok.Mono,
                fontSize = 11.sp
            )
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) Tok.Accent else Color.Transparent)
                .border(
                    1.dp,
                    if (selected) Tok.Accent else Tok.Border,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) CheckGlyph(Tok.OnAccent, 12.dp)
        }
    }
}

@Composable
private fun ToastHost(controller: StreamController, modifier: Modifier = Modifier) {
    val message = controller.toast
    var displayed by remember { mutableStateOf("") }

    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        displayed = current.text
        delay(1800)
        controller.clearToast(current.id)
    }

    Box(modifier.padding(bottom = 30.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(180))
        ) {
            Text(
                displayed,
                color = Tok.Bg,
                fontFamily = Tok.Mono,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Tok.Fg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 18.dp, vertical = 11.dp)
            )
        }
    }
}
