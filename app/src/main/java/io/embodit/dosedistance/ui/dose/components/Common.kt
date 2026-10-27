package io.embodit.dosedistance.ui.dose.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.dosedistance.ui.dose.debug.probe
import io.embodit.dosedistance.ui.theme.DdType

internal val RowH = 40.dp

/** SpatialUI's SegmentItem draws about 48 dp tall: a lower control spills into the next row (emulator, 2026-10-26). */
internal val SegmentH = 48.dp

/** An icon key (SpatialUI IconButton) of [size] dp; [container] null = fillTertiary. */
@Composable
internal fun Key(name: String, icon: Int, description: String, enabled: Boolean, size: Dp = RowH, iconSize: Dp = 22.dp, container: Color? = null, content: Color? = null, onClick: () -> Unit) {
    val ink = content ?: if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(containerColor = container ?: PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = size, height = size),
    ) { Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(iconSize), tint = ink) }
}

/** A SpatialUI SegmentControl of equal items, [width] wide and [height] high. */
@Composable
internal fun <T> Segments(items: List<T>, selected: T, width: Dp, enabled: Boolean, name: (T) -> String, onSelect: (T) -> Unit, height: Dp = SegmentH, title: @Composable (T) -> Unit) {
    // the control keeps about 4 dp of its own padding on each side (Furniture Fit's menu measurement)
    val itemW = (width - 8.dp) / items.size
    SegmentControl(modifier = Modifier.width(width).height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { item ->
            SegmentItem(
                selected = item == selected,
                onClick = { if (enabled) onSelect(item) },
                modifier = Modifier.width(itemW).fillMaxHeight().probe(name(item)),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = if (enabled) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary,
                    ),
                title = { title(item) },
            )
        }
    }
}

/** A capsule button with an optional icon and a word. */
@Composable
internal fun WordButton(name: String, icon: Int?, text: String, container: Color, content: Color, enabled: Boolean = true, height: Dp = RowH, minWidth: Dp = 88.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.probe(name),
        size = ButtonDefaults.Regular.copy(height = height, minWidth = minWidth),
        shape = CircleShape,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = content)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text, color = content, style = DdType.label, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun Spinner(size: Dp, color: Color) {
    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(size), color = color, progressSize = CircularProgressDefaults.circleProgressSize(size)) }
}
