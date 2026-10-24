package cn.imgozi.planetport.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cn.imgozi.planetport.R
import cn.imgozi.planetport.tunnel.TunnelStateBus
import cn.imgozi.planetport.ui.theme.PlanetFonts

/**
 * 数据面诊断页（iOS ApplyTraceView 1:1）：「最近一次聚合决策」applyTrace +
 * 「事件日志（最近 40 条）」eventLog 两段只读文本，caption 等宽、可选中复制，
 * 右上角一键复制全部；空显示「暂无」。
 *
 * 数据源 = TunnelStateBus 两流（推模式），页面无状态、无 VM。
 *
 * @param onBack 页面退栈回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplyTraceView(onBack: () -> Unit) {
    val trace by TunnelStateBus.applyTrace.collectAsState()
    val eventLog by TunnelStateBus.eventLog.collectAsState()
    // LocalClipboardManager：现代 LocalClipboard 为 suspend API，同步拷贝即可
    // （deprecation 处理归 T7 资源化任务，与 CopyableText 同策略）
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.trace_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString("$trace\n\n$eventLog"))
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.trace_copy),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TraceSection(title = stringResource(R.string.trace_last), content = trace)
            TraceSection(title = stringResource(R.string.trace_events), content = eventLog)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TraceSection(title: String, content: String) {
    val emptyLabel = stringResource(R.string.trace_empty)
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                content.ifEmpty { emptyLabel },
                style = PlanetFonts.MonoCaption,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
