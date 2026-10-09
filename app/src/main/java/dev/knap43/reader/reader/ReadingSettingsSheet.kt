package dev.knap43.reader.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.knap43.reader.data.FontChoice
import dev.knap43.reader.data.PageTheme
import dev.knap43.reader.data.ReadingSettings
import dev.knap43.reader.data.Spacing
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsSheet(
    settings: ReadingSettings,
    onChange: ((ReadingSettings) -> ReadingSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Page") {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PageTheme.entries.forEach { theme ->
                        ThemeSwatch(theme, selected = settings.theme == theme) {
                            onChange { it.copy(theme = theme) }
                        }
                    }
                }
            }

            Section("Text size") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { onChange { it.copy(fontScale = (it.fontScale - 0.1f).coerceAtLeast(0.6f)) } },
                    ) { Icon(Icons.Rounded.Remove, "Smaller") }
                    Text(
                        "${(settings.fontScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(88.dp),
                    )
                    FilledTonalIconButton(
                        onClick = { onChange { it.copy(fontScale = (it.fontScale + 0.1f).coerceAtMost(2.5f)) } },
                    ) { Icon(Icons.Rounded.Add, "Larger") }
                }
            }

            Section("Typeface") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FontChoice.entries.forEachIndexed { i, font ->
                        SegmentedButton(
                            selected = settings.font == font,
                            onClick = { onChange { it.copy(font = font) } },
                            shape = SegmentedButtonDefaults.itemShape(i, FontChoice.entries.size),
                        ) {
                            Text(
                                font.label,
                                fontFamily = when (font) {
                                    FontChoice.SERIF -> FontFamily.Serif
                                    FontChoice.SANS -> FontFamily.SansSerif
                                    FontChoice.ORIGINAL -> FontFamily.Default
                                },
                            )
                        }
                    }
                }
            }

            Section("Line spacing") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Spacing.entries.forEachIndexed { i, spacing ->
                        SegmentedButton(
                            selected = settings.spacing == spacing,
                            onClick = { onChange { it.copy(spacing = spacing) } },
                            shape = SegmentedButtonDefaults.itemShape(i, Spacing.entries.size),
                        ) { Text(spacing.label, maxLines = 1, fontSize = 13.sp) }
                    }
                }
                if (settings.font != FontChoice.ORIGINAL || settings.spacing != Spacing.ORIGINAL) {
                    Text(
                        "The book's own styling is relaxed so these choices can apply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Section("Reading") {
                SwitchRow(
                    title = "Hide status and navigation bars",
                    subtitle = "Swipe from an edge to see them",
                    checked = settings.hideSystemBars,
                ) { on -> onChange { it.copy(hideSystemBars = on) } }
                SwitchRow(
                    title = "Animate page turns",
                    subtitle = "Pages slide when you tap an edge",
                    checked = settings.animatePageTurns,
                ) { on -> onChange { it.copy(animatePageTurns = on) } }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ThemeSwatch(theme: PageTheme, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), CircleShape)
                .background(Color(theme.background))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("Aa", color = Color(theme.text), fontFamily = FontFamily.Serif, fontSize = 17.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            theme.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
