package cn.imgozi.planetport.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 弹层底部统一预留高度：内容不再贴屏幕底部（iOS large sheet 底部节奏）。 */
private val SheetBottomGap = 16.dp

/**
 * 弹层统一高度容器（对齐 iOS 全部 sheet 的 large detent 形态）：高度 =
 * sheet 内容区最大可用高度 - [SheetBottomGap]。最大可用高度由 sheet 自身的
 * contentWindowInsets（系统栏 + IME）派生——键盘弹出时随 insets 收缩，无需
 * 额外 imePadding；内容不足时底部留白，超长时由调用方内部的滚动容器
 * （verticalScroll / LazyColumn）承接。
 */
@Composable
fun SheetScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight - SheetBottomGap),
            content = content,
        )
    }
}
