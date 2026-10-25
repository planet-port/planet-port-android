package cn.imgozi.planetport.ui.prefs

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.BackgroundImageStore
import cn.imgozi.planetport.store.StreamTooLargeException
import cn.imgozi.planetport.store.readBounded
import cn.imgozi.planetport.ui.components.SectionCard
import cn.imgozi.planetport.ui.components.SheetScaffold
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 背景图选择弹层（iOS BackgroundImagePickerSheet 等价）：相册
 * （PickVisualMedia，无需相册权限）或 URL 下载；已设置时追加「清除背景图」。
 * 保存/清除统一走 [vm]（AppPrefs.backgroundImage 是视差行门控的唯一事实源，
 * 直写 store 会导致该布尔恒 false），成功后回调 [onApplied]（调用方关闭弹层
 * 并刷新缩略图），失败 footer 红字提示（文案由 VM 层本地化）。
 *
 * @param vm 偏好 VM：store 校验落盘 + AppPrefs 双写 + 错误文案本地化
 * @param hasImage 当前是否已设置背景图（决定清除区显隐）
 * @param onApplied 应用成功回调
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundImagePickerSheet(
    vm: PrefsViewModel,
    hasImage: Boolean,
    onApplied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlText by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 相册/URL 数据统一入口：VM 落盘（store 校验 + AppPrefs.backgroundImage 双写），
    // 失败回调本地化文案，成功关闭
    fun applyImage(data: ByteArray) {
        vm.applyBackgroundImage(data) { failure ->
            errorText = failure
            if (failure == null) onApplied()
        }
    }

    // 相册单选（对齐 iOS PHPicker filter .images selectionLimit 1），无需权限
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 有界读取（与 BackgroundImageStore.save 的 MAX_BYTES 校验同值）：相册
        // 资源大小不可信，无界 readBytes 是 OOM 入口，读过程中超限即断（读前即限）
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBounded(BackgroundImageStore.MAX_BYTES)
            }
        }.getOrElse { e ->
            errorText = context.getString(
                if (e is StreamTooLargeException) R.string.error_bg_tooLarge
                else R.string.error_bg_notImage
            )
            null
        }
        bytes?.let { applyImage(it) }
    }

    fun download() {
        val trimmed = urlText.trim()
        val parsed = runCatching { URL(trimmed) }.getOrNull()
        if (parsed == null || (parsed.protocol != "http" && parsed.protocol != "https")) {
            errorText = context.getString(R.string.bg_error_invalidUrl)
            return
        }
        downloading = true
        errorText = null
        scope.launch {
            val result = runCatching {
                val conn = parsed.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    // 有界读取（与 BackgroundImageStore.save 的 MAX_BYTES 校验同值）：
                    // 远程响应体长度不可信，读过程中超限即断，不等落盘后判
                    conn.inputStream.use { it.readBounded(BackgroundImageStore.MAX_BYTES) }
                } finally {
                    conn.disconnect()
                }
            }
            downloading = false
            when {
                // 恒定资源文案：HTTP 码/系统异常 message 不直显（T7b 范式），细节进日志
                result.isFailure -> {
                    Log.w("BgImage", "apply 失败", result.exceptionOrNull())
                    errorText = context.getString(
                        if (result.exceptionOrNull() is StreamTooLargeException) R.string.error_bg_tooLarge
                        else R.string.bg_error_generic
                    )
                }
                result.getOrDefault(ByteArray(0)).isEmpty() -> errorText =
                    context.getString(R.string.bg_error_emptyContent)
                else -> applyImage(result.getOrThrow())
            }
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 标题行：bg.title + 右上「完成」（iOS toolbar Done）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.bg_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }

                // —— 从相册选择 ——
                val choosePhoto = stringResource(R.string.bg_choosePhoto)
                Text(
                    stringResource(R.string.bg_albumHeader),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = choosePhoto) {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    outlined = true,
                ) {
                    PickerRow(
                        icon = {
                            Icon(
                                Icons.Filled.Photo,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        label = choosePhoto,
                    )
                }

                // —— 从 URL 下载 ——
                val downloadLabel = stringResource(R.string.bg_download)
                Text(
                    stringResource(R.string.bg_urlHeader),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://example.com/bg.jpg") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = downloadLabel,
                            enabled = urlText.isNotBlank() && !downloading,
                        ) { download() },
                    outlined = true,
                ) {
                    PickerRow(
                        icon = {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        label = downloadLabel,
                    ) {
                        if (downloading) {
                            Spacer(Modifier.weight(1f))
                            CircularProgressIndicator(Modifier.size(16.dp))
                        }
                    }
                }

                errorText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // —— 清除（已设置才显示，对齐 iOS if app.backgroundImage != nil）——
                if (hasImage) {
                    val clearLabel = stringResource(R.string.bg_clear)
                    SectionCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = clearLabel) {
                                // 清除走 VM：删文件 + AppPrefs.backgroundImage 归位（无失败分支）
                                vm.clearBackgroundImage()
                                onApplied()
                            },
                        outlined = true,
                    ) {
                        Text(
                            clearLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 选择行：图标 + 标签（iOS Label 行节奏）；[trailing] 供下载中转圈占用行尾。 */
@Composable
private fun PickerRow(
    icon: @Composable () -> Unit,
    label: String,
    trailing: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
        trailing()
    }
}
