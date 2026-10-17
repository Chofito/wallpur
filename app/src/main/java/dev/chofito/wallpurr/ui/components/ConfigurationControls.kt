package dev.chofito.wallpurr.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.storage.HiddenAutoLock
import dev.chofito.wallpurr.storage.LockStyle
import dev.chofito.wallpurr.storage.PowerSavePolicy
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntervalSelector(
    minutes: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfigurationSurface(
        icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
        title = stringResource(R.string.interval),
        supporting = stringResource(R.string.interval_hint),
        modifier = modifier,
    ) {
        ValueStepper(
            value = minutes,
            valueLabel = intervalLabel(minutes),
            range = 1..180,
            onSelect = onSelect,
        )
        val presets = listOf(5, 15, 30, 60)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            presets.forEachIndexed { index, preset ->
                SegmentedButton(
                    selected = minutes == preset,
                    onClick = { onSelect(preset) },
                    shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                    icon = {},
                    label = {
                        Text(if (preset == 60) "1 h" else "$preset m")
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSaveSelector(
    policy: PowerSavePolicy,
    onSelect: (PowerSavePolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfigurationSurface(
        icon = { Icon(Icons.Outlined.BatterySaver, contentDescription = null) },
        title = stringResource(R.string.power_save_behavior_title),
        supporting = stringResource(R.string.power_save_behavior_hint),
        modifier = modifier,
    ) {
        val options = listOf(
            PowerSavePolicy.IGNORE to stringResource(R.string.power_save_ignore),
            PowerSavePolicy.STATIC to stringResource(R.string.power_save_static),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = policy == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = stringResource(
                when (policy) {
                    PowerSavePolicy.IGNORE -> R.string.power_save_ignore_detail
                    PowerSavePolicy.STATIC -> R.string.power_save_static_detail
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HiddenAutoLockSelector(
    policy: HiddenAutoLock,
    onSelect: (HiddenAutoLock) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfigurationSurface(
        icon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        title = stringResource(R.string.hidden_auto_lock_title),
        supporting = stringResource(R.string.hidden_auto_lock_hint),
        modifier = modifier,
    ) {
        val afterMinutes = policy as? HiddenAutoLock.AfterMinutes
            ?: HiddenAutoLock.AfterMinutes(DEFAULT_HIDDEN_AUTO_LOCK_MINUTES)
        val options = listOf(
            HiddenAutoLock.OnLeave to stringResource(R.string.hidden_auto_lock_on_leave),
            afterMinutes to stringResource(R.string.hidden_auto_lock_after),
            HiddenAutoLock.Manual to stringResource(R.string.hidden_auto_lock_manual),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (option, label) ->
                // Three segments are ~90 dp wide on a phone: no check icon and a single line, so
                // the labels fit; the selected segment is already marked by its fill.
                SegmentedButton(
                    selected = policy::class == option::class,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = {},
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        if (policy is HiddenAutoLock.AfterMinutes) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                HiddenAutoLock.MINUTE_OPTIONS.forEach { minutes ->
                    FilterChip(
                        selected = policy.minutes == minutes,
                        onClick = { onSelect(HiddenAutoLock.AfterMinutes(minutes)) },
                        label = { Text(stringResource(R.string.hidden_auto_lock_minutes, minutes)) },
                    )
                }
            }
        }
        Text(
            text = when (policy) {
                HiddenAutoLock.OnLeave -> stringResource(R.string.hidden_auto_lock_on_leave_detail)
                is HiddenAutoLock.AfterMinutes ->
                    stringResource(R.string.hidden_auto_lock_after_detail, policy.minutes)
                HiddenAutoLock.Manual -> stringResource(R.string.hidden_auto_lock_manual_detail)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val DEFAULT_HIDDEN_AUTO_LOCK_MINUTES = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockAppearanceSelector(
    blurRadius: Int,
    opacity: Float,
    onBlurChange: (Int) -> Unit,
    onBlurCommit: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onOpacityCommit: () -> Unit,
    supportingText: String,
    modifier: Modifier = Modifier,
) {
    ConfigurationSurface(
        icon = { Icon(Icons.Outlined.BlurOn, contentDescription = null) },
        title = stringResource(R.string.lock_style_title),
        supporting = supportingText,
        modifier = modifier,
    ) {
        SettingValueHeader(
            label = stringResource(R.string.lock_blur),
            value = stringResource(
                R.string.lock_blur_level,
                blurRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS),
            ),
        )
        Slider(
            value = blurRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS).toFloat(),
            onValueChange = { onBlurChange(it.roundToInt()) },
            onValueChangeFinished = onBlurCommit,
            valueRange = 0f..LockStyle.MAX_BLUR_RADIUS.toFloat(),
            steps = LockStyle.MAX_BLUR_RADIUS - 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.lock_blur_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.lock_blur_soft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.lock_blur_medium),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.lock_blur_strong),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val darknessPercent = ((1f - opacity) * 100f)
            .roundToInt()
            .coerceIn(0, 20)
        SettingValueHeader(
            label = stringResource(R.string.lock_darkness),
            value = stringResource(R.string.lock_opacity_percent, darknessPercent),
        )
        Slider(
            value = darknessPercent.toFloat(),
            onValueChange = {
                val updated = it.roundToInt().coerceIn(0, 20)
                onOpacityChange(1f - updated / 100f)
            },
            onValueChangeFinished = onOpacityCommit,
            valueRange = 0f..20f,
            steps = 19,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.lock_darkness_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.lock_darkness_max),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (blurRadius > 0 || opacity < 0.999f) {
            Text(
                stringResource(R.string.lock_style_animation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun SettingValueHeader(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
fun ValueStepper(
    value: Int,
    valueLabel: String,
    range: IntRange,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        FilledTonalIconButton(
            onClick = { onSelect((value - 1).coerceIn(range)) },
            enabled = value > range.first,
        ) {
            Icon(
                Icons.Outlined.Remove,
                contentDescription = stringResource(R.string.decrease_value),
            )
        }
        Text(
            valueLabel,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(
            onClick = { onSelect((value + 1).coerceIn(range)) },
            enabled = value < range.last,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = stringResource(R.string.increase_value),
            )
        }
    }
}

@Composable
private fun ConfigurationSurface(
    icon: @Composable () -> Unit,
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(10.dp)) { icon() }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) {
        stringResource(R.string.interval_hours, minutes / 60)
    } else {
        stringResource(R.string.interval_minutes, minutes)
    }
