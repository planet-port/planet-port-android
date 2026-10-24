package cn.imgozi.planetport.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.PlanetKind
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.components.CopyableValue
import cn.imgozi.planetport.ui.components.OfficialPlanetIcon
import cn.imgozi.planetport.ui.planet.PlanetIcon
import cn.imgozi.planetport.ui.theme.HomeMetrics
import cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.theme.tertiaryTextColor

/** systemGray（iOS 行星卡右下齿轮用色：固定装饰灰，不随语义色变化）。 */
private val GearGray = Color(0xFF8E8E93)

/**
 * 行星身份卡（对齐 iOS PlanetCardView.swift）：72dp 图标磁贴 + 名称 20sp semibold +
 * ID 行（monospaced tertiary，点击复制；moon>0 追加「卫星 x N」）。无 chevron，
 * 按压缩放 0.97 为唯一可点暗示（缩放由父级传入的 [interactionSource] 驱动，
 * 点击导航归父级——对齐 iOS 整卡 onTapGesture 在 HomeView）。
 *
 * 预览形态（添加页占位）：虚线边框 + 淡底，无角标、无齿轮、无按压反馈。
 */
@Composable
fun PlanetCardView(
    planet: PlanetProfile,
    nodeId: String,
    moonCount: Int,
    showBadge: Boolean,
    badgeInUse: Boolean,
    modifier: Modifier = Modifier,
    isPreview: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
) {
    val tertiary = tertiaryTextColor(isSystemInDarkTheme())
    // 预览态描边用色在组合期取值（drawBehind 内非 composable 上下文）
    val previewFill = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
    val previewStroke = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val pressed = interactionSource?.collectIsPressedAsState()?.value ?: false
    val scale by animateFloatAsState(
        targetValue = if (pressed && !isPreview) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 450f),
        label = "planetCardPress",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .then(
                if (isPreview) {
                    // 预览占位：淡底 + 1.5dp 虚线 [6,4] 圆角 16（iOS PlanetCardView:29-39）
                    Modifier
                        .drawBehind {
                            drawRoundRect(
                                color = previewFill,
                                cornerRadius = CornerRadius(HomeMetrics.radiusPreview.toPx()),
                            )
                            drawRoundRect(
                                color = previewStroke,
                                cornerRadius = CornerRadius(HomeMetrics.radiusPreview.toPx()),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 4.dp.toPx())
                                    ),
                                ),
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            when (planet.kind) {
                PlanetKind.official -> OfficialPlanetIcon(Modifier.size(HomeMetrics.iconTileSize))
                PlanetKind.custom -> CustomPlanetTile(planet)
            }
            if (showBadge && !isPreview) {
                StatusBadge(
                    badgeInUse,
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-6).dp),
                )
            }
            if (!isPreview) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = GearGray,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .size(14.dp),
                )
            }
        }
        Text(
            // 官方行星存储名不翻译，显示侧映射本地化文案（iOS PlanetProfile.displayName）
            if (planet.kind == PlanetKind.official) {
                stringResource(R.string.planet_official_name)
            } else {
                planet.name
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        IdLine(nodeId, moonCount, tertiary)
    }
}

/** 72dp 私有行星磁贴：底色实底（自定义/哈希）+ 白动物图 38dp，圆角 20（iOS :30-40）。 */
@Composable
private fun CustomPlanetTile(planet: PlanetProfile) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(HomeMetrics.iconTileSize)
            .clip(RoundedCornerShape(HomeMetrics.radiusLarge))
            .background(PlanetIcon.tint(planet)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(PlanetIcon.drawableId(context, PlanetIcon.animalKey(planet))),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(38.dp),
        )
    }
}

/**
 * 骑缝角标（iOS :125-137）：VPN 在跑 =「使用中」（主色实底白字），
 * 未跑 =「上次使用」（卡片底 secondary 字 + 0.5dp 发丝描边）。
 */
@Composable
private fun StatusBadge(inUse: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    // 描边用色在组合期取值（drawBehind 内非 composable 上下文）
    val hairline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (inUse) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .then(
                if (inUse) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = hairline,
                            cornerRadius = CornerRadius(size.height),
                            style = Stroke(0.5.dp.toPx()),
                        )
                    }
                },
            )
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            stringResource(if (inUse) R.string.planetcard_inUse else R.string.planetcard_lastUsed),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (inUse) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ID 行（iOS :92-121）：16sp monospaced tertiary，点击复制（无常驻图标，
 * 成功瞬间短暂显示对勾）；moon>0 时以间隔号「·」跟随「卫星 x N」。
 */
@Composable
private fun IdLine(nodeId: String, moonCount: Int, tertiary: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CopyableValue(
            text = nodeId,
            showsIcon = false,
            style = PlanetFonts.MonoId,
            color = tertiary,
        )
        if (moonCount > 0) {
            Text("·", style = MaterialTheme.typography.bodyMedium, color = tertiary)
            Text(
                stringResource(R.string.planetcard_moonCount, moonCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 官方行星整块磁贴（iOS 资产 OfficialPlanetIcon：线稿带环行星 · 石绿底，浅深自适应）。
 * 图标本体已提升至 ui.components.OfficialPlanetIcon 与行星设置页共用。
 */
