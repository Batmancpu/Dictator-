/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Gif
import org.florisboard.lib.compose.icons.Sticker
import androidx.compose.ui.graphics.vector.ImageVector
import dev.patrickgold.florisboard.R

/**
 * An action that can be placed in the legacy dictation layout's configurable action row (issue #183 /
 * #194). The user arranges these freely in Settings → Dictate → Dictation layout; the layout renders the
 * chosen ones left-to-right, wrapping onto a second row once there are more than [MAX_PER_ROW] of them
 * (issue #226 — see [rows]). Enum names are persisted (comma-separated) in
 * [dev.patrickgold.florisboard.app.AppPrefs.Dictate.legacyActionRow], so DON'T rename them.
 */
enum class LegacyEditAction {
    SELECT_ALL,
    UNDO,
    REDO,
    CUT,
    COPY,
    PASTE,
    EMOJI,
    NUMBERS,
    LANGUAGE,
    HISTORY,
    REINSERT,
    GIF,
    STICKER,
    CLIPBOARD,
    SWITCH,
    BACKSPACE,
    // Jump to the very start or end of the field (issue #335). They matter more here than on the
    // keyboard: this layout has no arrow keys at all, only the space bar's swipe, which walks the
    // cursor one character at a time.
    HOME,
    END,
    // The text editing panel (issue #386). For the same reason HOME/END matter here: the classic layout
    // has no arrow keys at all, so this is the only place in it where a cursor can be steered.
    EDITING,
    // Scan text (issue #390). The classic layout is the one people use when they want the keyboard out
    // of the way, which is exactly the situation where retyping an IBAN off a letter is worst.
    SCAN;

    val icon: ImageVector
        get() = when (this) {
            SELECT_ALL -> Icons.Default.SelectAll
            UNDO -> Icons.AutoMirrored.Filled.Undo
            REDO -> Icons.AutoMirrored.Filled.Redo
            CUT -> Icons.Default.ContentCut
            COPY -> Icons.Default.ContentCopy
            PASTE -> Icons.Default.ContentPaste
            EMOJI -> Icons.Default.EmojiEmotions
            NUMBERS -> Icons.Default.Numbers
            LANGUAGE -> Icons.Default.Language
            HISTORY -> Icons.Default.History
            REINSERT -> Icons.Default.Replay
            GIF -> Icons.Outlined.Gif
            STICKER -> Icons.Outlined.Sticker
            CLIPBOARD -> Icons.AutoMirrored.Outlined.Assignment
            SWITCH -> Icons.Default.KeyboardHide
            BACKSPACE -> Icons.Default.Backspace
            HOME -> Icons.Default.VerticalAlignTop
            END -> Icons.Default.VerticalAlignBottom
            EDITING -> Icons.Default.EditNote
            SCAN -> Icons.Outlined.DocumentScanner
        }

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            SELECT_ALL -> R.string.dictate__legacy_action_select_all
            UNDO -> R.string.dictate__legacy_action_undo
            REDO -> R.string.dictate__legacy_action_redo
            CUT -> R.string.dictate__legacy_action_cut
            COPY -> R.string.dictate__legacy_action_copy
            PASTE -> R.string.dictate__legacy_action_paste
            EMOJI -> R.string.dictate__legacy_action_emoji
            NUMBERS -> R.string.dictate__legacy_action_numbers
            LANGUAGE -> R.string.dictate__legacy_action_language
            HISTORY -> R.string.dictate__legacy_action_history
            REINSERT -> R.string.dictate__legacy_action_reinsert
            GIF -> R.string.dictate__legacy_action_gif
            STICKER -> R.string.sticker__title
            CLIPBOARD -> R.string.clipboard__header_title
            SWITCH -> R.string.dictate__legacy_action_switch
            // Reuses the existing backspace content-description string (already localised everywhere).
            BACKSPACE -> R.string.dictate__legacy_backspace
            // Same buttons as in the Smartbar, so they carry the same names rather than a second set.
            HOME -> R.string.quick_action__move_start_of_page
            END -> R.string.quick_action__move_end_of_page
            EDITING -> R.string.quick_action__ime_ui_mode_editing
            SCAN -> R.string.quick_action__ime_ui_mode_scan
        }

    companion object {
        /** The original fixed row — the default when the user hasn't customised it. */
        val DEFAULT: List<LegacyEditAction> =
            listOf(SELECT_ALL, UNDO, REDO, CUT, COPY, PASTE, EMOJI, NUMBERS)

        /** Most action rows fit about this many keys comfortably across a phone. */
        // Raised from 8 when stickers and the clipboard joined: the default row already held
        // eight, so at the old limit both new buttons were greyed out for everyone who had
        // never edited the row — which is most people.
        const val MAX_PER_ROW = 10

        /** How many rows the layout will ever wrap the chosen actions onto (issue #226). */
        // Two, because the classic layout is ~215dp against the ~260–300dp keyboard it replaces: one
        // extra 46dp row still leaves it shorter than that, a third would not. It is also enough to
        // hold every action there is, which is what the request was actually about.
        const val MAX_ROWS = 2

        /** The most actions that can be placed at all — [MAX_ROWS] full rows. The editor caps here. */
        const val MAX_TOTAL = MAX_PER_ROW * MAX_ROWS

        /**
         * Splits the chosen [actions] across the rows the layout draws (issue #226).
         *
         * Up to [MAX_PER_ROW] they stay on one row — the layout everyone had before. Beyond that they
         * wrap onto a second row, **balanced** rather than filled-then-spilled: eleven buttons come out
         * 6 + 5, not 10 + 1. The user never arranges this, they only pick buttons and an order; where the
         * break falls is the layout's business.
         */
        fun rows(actions: List<LegacyEditAction>): List<List<LegacyEditAction>> = when {
            actions.isEmpty() -> emptyList()
            actions.size <= MAX_PER_ROW -> listOf(actions)
            else -> {
                val first = (actions.size + 1) / 2
                listOf(actions.take(first), actions.drop(first))
            }
        }

        /** Serialises [actions] for the pref (comma-separated enum names). */
        fun serialize(actions: List<LegacyEditAction>): String = actions.joinToString(",") { it.name }

        /**
         * Parses the pref value back into actions, ignoring unknown names; empty falls back to [DEFAULT].
         *
         * An action appears at most once and the list is cut to [MAX_TOTAL]: the editor cannot produce
         * anything else, but a hand-edited or restored pref can, and a duplicate key would be a button
         * that does not answer where it is tapped.
         */
        fun parse(raw: String): List<LegacyEditAction> {
            val parsed = raw.split(',')
                .mapNotNull { token -> entries.firstOrNull { it.name == token.trim() } }
                .distinct()
                .take(MAX_TOTAL)
            return parsed.ifEmpty { DEFAULT }
        }
    }
}
