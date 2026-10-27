package cn.imgozi.planetport.ui.qrtransfer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 分片方块阵列（iOS ShardGridView）。两种形态：
 * 发送端：[current] 高亮当前帧，[onTap] 非空时可点跳转（手动模式）。
 * 接收端：[received] 填充已收格，未收格描边强调缺片。
 * 块上直接写分片号，替代独立的数字分片提示。
 * 布局为流式换行、每行居中，方块定宽不撑满整行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShardGrid(
    total: Int,
    current: Int? = null,
    received: Set<Int> = emptySet(),
    onTap: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (i in 1..total) {
            val isCurrent = current == i
            val isReceived = i in received
            // current==null 即接收端形态：未收格描边
            val isMissing = current == null && !isReceived
            val bg = when {
                isCurrent -> MaterialTheme.colorScheme.primary
                isReceived -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 22.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .then(
                        if (isMissing) {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(if (onTap != null) Modifier.clickable { onTap(i) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$i",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
