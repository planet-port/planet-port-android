package cn.imgozi.planetport.ui.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.ui.components.SectionCard
import cn.imgozi.planetport.ui.theme.PlanetFonts

/**
 * iOS Form（分组列表）节的等价形态：header caption 次要色 + 卡片 + footer caption 次要色。
 * header/footer 水平缩进 16（iOS grouped list 的 header inset 纪律）；
 * 行内边距 14、行高下限 44（触控目标纪律）。
 *
 * 独立成 `ui.form` 包的原因：四个设置/详情页共用，且不能挤进 T1 的
 * `ui.components`（波 2 并行任务各有建组件的可能，独立包零合并冲突）。
 */
@Composable
fun FormSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
            )
        }
        SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            content()
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 7.dp, bottom = 2.dp),
            )
        }
    }
}

/** Form 信息行：title 左 + value 右等宽次要色（iOS NetworkDetailView.row / PlanetSettingsView.row）。 */
@Composable
fun FormRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = PlanetFonts.MonoBody,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        Text(
            value,
            style = valueStyle,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** Form 开关行（iOS Form 内 Toggle）：title 左 + Switch 右。 */
@Composable
fun FormToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = titleColor,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
