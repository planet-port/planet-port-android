package cn.imgozi.planetport.ui.qrtransfer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.R
import cn.imgozi.planetport.qrtransfer.QRRender
import cn.imgozi.planetport.qrtransfer.QRTransfer
import cn.imgozi.planetport.qrtransfer.QRTransferException
import cn.imgozi.planetport.ui.components.SheetScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 分享端（iOS QRShareView）：展示分片二维码序列。单帧退化为静态码（无控件）；
 * 多帧提供速度四档与手动模式（点方块阵列直接跳帧）。编码与渲染均在后台
 * 线程逐张完成，首张就绪即显示，避免长时间空白等待。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QRShareSheet(
    title: String,
    data: ByteArray,
    onDismiss: () -> Unit,
) {
    var frames by remember { mutableStateOf(listOf<String>()) }
    var images by remember { mutableStateOf(listOf<Bitmap>()) }
    var tooLarge by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    var speed by remember { mutableStateOf(0.5) }
    var manual by remember { mutableStateOf(false) }
    val speeds = remember { listOf(0.3, 0.5, 0.8, 1.2) }
    // 屏宽 dp（iOS UIScreen.main.bounds 等价）
    val sideDp = LocalConfiguration.current.screenWidthDp * 0.72f

    // 后台编码分片，再逐张后台渲染、逐张回填显示
    LaunchedEffect(data) {
        val maxModules = QRTransfer.recommendedMaxModules(sideDp)
        try {
            val encoded = withContext(Dispatchers.Default) { QRTransfer.encode(data, maxModules) }
            frames = encoded
            index = 0
            for (frame in encoded) {
                val image = withContext(Dispatchers.Default) { QRRender.generate(frame)?.first }
                if (image != null) images = images + image
            }
        } catch (_: QRTransferException) {
            tooLarge = true
        }
    }

    // 渲染全部就绪后才允许自动播放启动（key 与 iOS .task(id:) 一致）
    LaunchedEffect(manual, speed, frames.size, images.size) {
        if (manual || frames.size <= 1 || images.size != frames.size) return@LaunchedEffect
        while (true) {
            delay((speed * 1000).toLong())
            index = (index + 1) % frames.size
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 统一 large detent（屏高-16dp）：跳过半展开，直接全高
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // 头部：居中标题 + 右「完成」（对齐 iOS navigationTitle + confirmationAction）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.qrtransfer_done))
                    }
                }

                if (frames.isEmpty()) {
                    if (tooLarge) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            stringResource(R.string.qrtransfer_tooLarge),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.qrtransfer_tooLargeHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.qrtransfer_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 已渲染图片按 index 取；该帧未就绪时取最后一张已生成的
                    if (images.isNotEmpty()) {
                        val current = images[minOf(index, images.size - 1)]
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription = null,
                            filterQuality = FilterQuality.None, // iOS interpolation(.none)
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .aspectRatio(1f),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    if (images.size < frames.size) {
                        Text(
                            stringResource(
                                R.string.qrtransfer_generatingProgress,
                                images.size,
                                frames.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (frames.size > 1) {
                        // 方块阵列顶替原数字分片提示的位置
                        ShardGrid(
                            total = frames.size,
                            current = index + 1,
                            onTap = if (manual) {
                                { oneBased -> index = (oneBased - 1 + frames.size) % frames.size }
                            } else {
                                null
                            },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        // 控件区：手动开关 + 速度档（手动时隐藏）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(checked = manual, onCheckedChange = { manual = it })
                            Text(
                                stringResource(R.string.qrtransfer_manual),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (!manual) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.qrtransfer_speed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                SingleChoiceSegmentedButtonRow {
                                    speeds.forEachIndexed { i, s ->
                                        SegmentedButton(
                                            selected = speed == s,
                                            onClick = { speed = s },
                                            shape = SegmentedButtonDefaults.itemShape(i, speeds.size),
                                        ) {
                                            Text("%.1fs".format(s))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
