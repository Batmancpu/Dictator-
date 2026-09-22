/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.smartbar

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.DefaultComputingEvaluator
import dev.patrickgold.florisboard.ime.keyboard.computeImageVector
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickAction
import dev.patrickgold.florisboard.ime.smartbar.quickaction.QuickActionSecondActions
import dev.patrickgold.florisboard.ime.smartbar.quickaction.computeDisplayName
import dev.patrickgold.florisboard.ime.smartbar.quickaction.keyData
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.florisScrollbar
import org.florisboard.lib.compose.header
import org.florisboard.lib.compose.stringRes

/**
 * Which action each Smartbar button runs when it is held (issue #385).
 *
 * A grid rather than a list of dropdowns, because the task is *connecting two things*: tap the button
 * you will hold, tap what it should run, and the two tiles become one with the second action in its
 * corner. The paired action leaves the grid — that is the whole feedback, and it is also why the
 * no-chain rule needs no explaining here: an action that is already inside another tile cannot be
 * picked as a host, because it is not standing on its own any more.
 *
 * Leaving the grid is not leaving the Smartbar. Both actions keep their own button; freeing a slot is
 * still the actions editor's job, which is what the header line says while nothing is selected.
 */
@Composable
fun SmartbarSecondActionsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__smartbar__second_actions__title)
    scrollable = false

    val prefs by FlorisPreferenceStore

    content {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val pairs by prefs.smartbar.actionSecondActions.collectAsState()
        // Read once, like the actions editor does: this screen does not edit the arrangement, it only
        // borrows its order so the grid reads the way the user's own bar does.
        val arrangement = remember { prefs.smartbar.actionArrangement.get() }
        // The incognito action asks the evaluator for a context before it picks between the crossed-out
        // and the plain icon, and the default evaluator has none.
        val evaluator = remember(context) {
            object : ComputingEvaluator by DefaultComputingEvaluator {
                override fun context(): Context? = context
            }
        }

        /** The action currently waiting for a second one, or null while nothing is being paired. */
        var armed by remember { mutableStateOf<QuickAction?>(null) }

        val order = remember(arrangement) {
            (listOfNotNull(arrangement.stickyAction) + arrangement.dynamicActions + arrangement.hiddenActions)
                .map { it.keyData().code }
        }
        fun List<QuickAction>.inBarOrder() = sortedBy { action ->
            order.indexOf(action.keyData().code).let { if (it < 0) Int.MAX_VALUE else it }
        }

        val absorbed = pairs.childCodes()
        val hosts = remember(pairs, order) {
            QuickActionSecondActions.EligibleHosts
                .filter { it.keyData().code !in absorbed }
                .inBarOrder()
        }
        // Repeating keys and the mic: they can be somebody's second action but can never carry one.
        val childOnly = remember(pairs, order) {
            val hostCodes = QuickActionSecondActions.EligibleHosts.map { it.keyData().code }.toSet()
            QuickActionSecondActions.KnownActions
                .filter { it.keyData().code !in hostCodes && it.keyData().code !in absorbed }
                .inBarOrder()
        }
        val candidates = remember(armed, pairs) {
            armed?.let { host -> pairs.eligibleChildrenFor(host).map { it.keyData().code }.toSet() }
        }

        fun pair(child: QuickAction) {
            val host = armed ?: return
            armed = null
            coroutineScope.launch { prefs.smartbar.actionSecondActions.set(pairs.with(host, child)) }
        }

        fun unpair(host: QuickAction) {
            coroutineScope.launch { prefs.smartbar.actionSecondActions.set(pairs.with(host, null)) }
        }

        val gridState = rememberLazyGridState()
        Column(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                modifier = Modifier
                    .weight(1f)
                    .florisScrollbar(gridState),
                state = gridState,
                columns = GridCells.Adaptive(88.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                header(key = "header") {
                    HeaderLine(
                        armedName = armed?.computeDisplayName(evaluator),
                        onCancel = { armed = null },
                    )
                }
                items(hosts, key = { it.keyData().code }) { action ->
                    val code = action.keyData().code
                    val child = pairs.childOf(code)
                    ActionTile(
                        modifier = Modifier.animateItem(),
                        action = action,
                        second = child,
                        evaluator = evaluator,
                        isArmed = armed?.keyData()?.code == code,
                        enabled = when {
                            armed == null -> true
                            armed?.keyData()?.code == code -> true
                            else -> code in (candidates ?: emptySet())
                        },
                        onClick = {
                            when {
                                armed?.keyData()?.code == code -> armed = null
                                armed != null -> pair(action)
                                child != null -> unpair(action)
                                else -> armed = action
                            }
                        },
                    )
                }
                if (childOnly.isNotEmpty()) {
                    header(key = "child-only") {
                        Text(
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 16.dp, end = 4.dp, bottom = 4.dp),
                            text = stringRes(R.string.settings__smartbar__second_actions__section_child_only),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(childOnly, key = { it.keyData().code }) { action ->
                        ActionTile(
                            modifier = Modifier.animateItem(),
                            action = action,
                            second = null,
                            evaluator = evaluator,
                            isArmed = false,
                            // They are only ever a choice, never a starting point.
                            enabled = armed != null && action.keyData().code in (candidates ?: emptySet()),
                            onClick = { pair(action) },
                        )
                    }
                }
            }
        }
    }
}

/** Says what to do next: the rule while nothing is selected, the question while something is. */
@Composable
private fun HeaderLine(armedName: String?, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        if (armedName == null) {
            Text(
                text = stringRes(R.string.settings__smartbar__second_actions__note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringRes(
                    R.string.settings__smartbar__second_actions__hint_pick_second,
                    "action" to armedName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onCancel) {
                Text(stringRes(R.string.action__cancel))
            }
        }
    }
}

@Composable
private fun ActionTile(
    action: QuickAction,
    second: QuickAction?,
    evaluator: ComputingEvaluator,
    isArmed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val color = when {
        isArmed -> MaterialTheme.colorScheme.secondaryContainer
        second != null -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    // Appears by growing out of the corner, so a pairing reads as the two tiles snapping together
    // rather than as one of them quietly vanishing.
    val badgeScale by animateFloatAsState(
        targetValue = if (second != null) 1f else 0f,
        animationSpec = spring(),
        label = "secondActionBadge",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .padding(4.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .then(
                if (isArmed) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = color,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                tileIcon(action, evaluator)?.let { icon ->
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(28.dp))
                }
                second?.let { child ->
                    tileIcon(child, evaluator)?.let { icon ->
                        // On its own disc in the tile's colour. A corner glyph on a centred icon always
                        // overlaps it a little, and without the disc the two line drawings ran into each
                        // other and read as one unrecognisable symbol.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .scale(badgeScale)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(color),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            Text(
                text = action.computeDisplayName(evaluator),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The action's icon, with the one exception named rather than left blank: the number pad hands out its
 * icon only to an evaluator that is drawing the Smartbar, and no settings evaluator ever is.
 */
@Composable
private fun tileIcon(action: QuickAction, evaluator: ComputingEvaluator): ImageVector? {
    val data = action.keyData()
    return evaluator.computeImageVector(data)
        ?: Icons.Default.Dialpad.takeIf { data.code == KeyCode.VIEW_NUMERIC_ADVANCED }
}
