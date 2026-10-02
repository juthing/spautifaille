package com.spautifaille.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spautifaille.ui.R
import com.spautifaille.ui.common.LocalAppHaptics
import com.spautifaille.ui.components.IconTone
import com.spautifaille.ui.components.ToneIconCircle
import com.spautifaille.ui.theme.ScreenHorizontalPadding
import com.spautifaille.ui.theme.Spacing

/** Libellé d'un groupe d'options sur une sous-page. */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, top = Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** Ligne de la page d'accueil : cercle tonal, titre, résumé dynamique. */
@Composable
internal fun SettingsCategoryRow(
    category: SettingsCategory,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAppHaptics.current
    Surface(
        onClick = {
            haptics.click()
            onClick()
        },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            ToneIconCircle(icon = category.icon, tone = category.tone)
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Ligne à interrupteur : toute la ligne est cliquable, l'interrupteur n'est qu'indicatif (accessibilité). */
@Composable
internal fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: IconTone = IconTone.Primary,
    enabled: Boolean = true,
) {
    val haptics = LocalAppHaptics.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { ToneIconCircle(icon = icon, tone = tone, size = 40.dp) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = {
                haptics.toggle(it)
                onCheckedChange(it)
            },
        ),
    )
}

/** Ligne qui mène à une autre page ou ouvre un dialogue. */
@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: IconTone = IconTone.Primary,
    showChevron: Boolean = true,
) {
    val haptics = LocalAppHaptics.current
    Surface(
        onClick = {
            haptics.click()
            onClick()
        },
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = summary?.let { { Text(it) } },
            leadingContent = { ToneIconCircle(icon = icon, tone = tone, size = 40.dp) },
            trailingContent = if (showChevron) {
                { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
            } else {
                null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Ligne d'information non interactive (À propos). */
@Composable
internal fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    tone: IconTone = IconTone.Neutral,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { ToneIconCircle(icon = icon, tone = tone, size = 40.dp) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

/**
 * Groupe de boutons radio (choix unique) directement sur la page. Un cran haptique accompagne un nouveau choix.
 * Les options pour lesquelles [isEnabled] renvoie faux sont grisées et non sélectionnables (leur [description]
 * doit alors en expliquer la raison).
 */
@Composable
internal fun <T> SettingsRadioGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: (@Composable (T) -> String?)? = null,
    isEnabled: (T) -> Boolean = { true },
) {
    val haptics = LocalAppHaptics.current
    Column(modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { option ->
            val isSelected = option == selected
            val optionDescription = description?.invoke(option)
            val enabled = isEnabled(option)
            val contentAlpha = if (enabled) 1f else DisabledContentAlpha
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            if (!isSelected) {
                                haptics.tick()
                                onSelect(option)
                            }
                        },
                    )
                    .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                Column(Modifier.padding(start = Spacing.m).alpha(contentAlpha)) {
                    Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    if (optionDescription != null) {
                        Text(
                            optionDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Bloc d'explication discret (icône d'information + texte) sous un groupe d'options. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 256 -> « 256 Mo », 1024 -> « 1 Go », 1536 -> « 1,5 Go ». */
@Composable
internal fun formatCacheSize(sizeMb: Int): String =
    if (sizeMb >= 1024) {
        if (sizeMb % 1024 == 0) {
            stringResource(R.string.set_size_gb, sizeMb / 1024)
        } else {
            stringResource(R.string.set_size_gb_decimal, sizeMb / 1024.0)
        }
    } else {
        stringResource(R.string.set_size_mb, sizeMb)
    }

/** Résout les fragments de [settingsSummary] et les joint par « · ». */
@Composable
internal fun List<SummaryPart>.resolveSummary(): String {
    val parts = map { part ->
        when (part) {
            is SummaryPart.Text -> stringResource(part.res)
            is SummaryPart.CacheSize -> stringResource(R.string.set_summary_cache, formatCacheSize(part.sizeMb))
            is SummaryPart.Version ->
                if (part.name.isBlank()) stringResource(R.string.set_summary_version_unknown)
                else stringResource(R.string.set_summary_version, part.name)
        }
    }
    return parts.joinToString(separator = " · ")
}

/** Opacité du contenu d'une option désactivée (valeur M3 des composants désactivés). */
private const val DisabledContentAlpha = 0.38f
