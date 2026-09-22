/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.input.RepeatableKeyCodes
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable

/** One pairing: holding [host] runs [action] instead of the tap [host] would otherwise deliver. */
@Serializable
data class QuickActionSecondAction(
    val host: QuickAction,
    val action: QuickAction,
)

/**
 * Which Smartbar action each button runs when it is held (issue #385).
 *
 * Deliberately **not** a fourth field on [QuickActionArrangement]: a pairing does not move anything.
 * The paired action keeps its own button wherever the user put it, and whoever wants the slot back
 * hides it in the actions editor as before. That keeps the arrangement's `contains` / `distinct()`
 * and its three migration paths untouched — a child that lived only in a new field would be absent
 * from `contains`, and `Serializer.deserialize`'s "append actions added since this was saved" step
 * would hand it back as a standalone button on every read.
 *
 * The pairing is free-form on purpose. HeliBoard ships a fixed table because its toolbar is fixed;
 * ours is arranged by the user, so a curated pair is a guess about ten of thirty-odd actions — and a
 * pairing someone set themselves needs no explaining, which is the very thing a fixed table has to
 * document under "hidden features".
 */
@Serializable
data class QuickActionSecondActions(val pairs: List<QuickActionSecondAction> = emptyList()) {
    // Read once per button per recomposition, so it is built once per instance rather than per call.
    private val byHostCode: Map<Int, QuickAction> by lazy {
        pairs.associate { it.host.keyData().code to it.action }
    }

    /** What holding the button for [hostCode] runs, or null when that button is a plain tap. */
    fun childOf(hostCode: Int): QuickAction? = byHostCode[hostCode]

    /** The codes that currently hold a second action. */
    fun hostCodes(): Set<Int> = pairs.map { it.host.keyData().code }.toSet()

    /** The codes that are currently somebody's second action. */
    fun childCodes(): Set<Int> = pairs.map { it.action.keyData().code }.toSet()

    /**
     * This pairing with [host]'s second action set to [action], or cleared when [action] is null.
     * Sanitised, so the write path cannot create a chain any more than a restored backup can.
     */
    fun with(host: QuickAction, action: QuickAction?): QuickActionSecondActions {
        val hostCode = host.keyData().code
        val rest = pairs.filterNot { it.host.keyData().code == hostCode }
        val next = if (action == null) rest else rest + QuickActionSecondAction(host, action)
        return QuickActionSecondActions(next).sanitized()
    }

    /**
     * What [host] may be given, for the picker: everything the app knows, minus [host] itself and
     * minus anything that already hosts a second action of its own (see the no-chain rule below).
     */
    fun eligibleChildrenFor(host: QuickAction): List<QuickAction> {
        val hostCode = host.keyData().code
        val taken = hostCodes() - hostCode
        return KnownActions.filter { it.keyData().code != hostCode && it.keyData().code !in taken }
    }

    /**
     * The pairing with every unusable entry dropped.
     *
     * Runs on every read rather than only on the picker, because a restored backup and a
     * hand-edited datastore reach the same field, and the rules here are not cosmetic:
     *
     * - a **repeating** host would silently lose its hold-to-repeat, since the input event dispatcher
     *   enters the repeat loop only when the long press declines the press;
     * - the **mic** as a host would fight `DictateHoldTouch`, which owns that whole gesture for
     *   push-to-talk, file transcription and the local model;
     * - a **chain** (A holds B, B holds C) has no button left to explain it.
     *
     * Everything else falls out for free: actions the app does not know are dropped by the
     * [KnownActionCodes] check, which is also why NOOP, the drag marker, the overflow toggle and the
     * retired line-start/line-end codes need no rule of their own.
     */
    fun sanitized(): QuickActionSecondActions {
        val hosts = mutableSetOf<Int>()
        val children = mutableSetOf<Int>()
        val kept = mutableListOf<QuickActionSecondAction>()
        for (pair in pairs) {
            val host = pair.host as? QuickAction.InsertKey ?: continue
            val action = pair.action as? QuickAction.InsertKey ?: continue
            val hostCode = host.data.code
            val childCode = action.data.code
            if (hostCode !in EligibleHostCodes) continue
            if (childCode !in KnownActionCodes) continue
            if (hostCode == childCode) continue
            // First pairing for a host wins, and neither side may already stand on the other rung.
            if (hostCode in hosts || hostCode in children || childCode in hosts) continue
            hosts += hostCode
            children += childCode
            kept += pair
        }
        return if (kept.size == pairs.size) this else QuickActionSecondActions(kept)
    }

    companion object {
        val Default = QuickActionSecondActions(emptyList())

        /**
         * Every action the app offers. The default arrangement is the registry — it is the one list
         * that must name every action for the editor to be able to show it — so it is read here
         * rather than copied.
         */
        val KnownActions: List<QuickAction> = with(QuickActionArrangement.Default) {
            (listOfNotNull(stickyAction) + dynamicActions + hiddenActions)
        }.filterIsInstance<QuickAction.InsertKey>()

        private val KnownActionCodes: Set<Int> = KnownActions.map { it.keyData().code }.toSet()

        /** The actions that may carry a second one. See [sanitized] for why these two are out. */
        val EligibleHosts: List<QuickAction> = KnownActions.filterNot {
            it.keyData().code in RepeatableKeyCodes || it.keyData().code == KeyCode.IME_UI_MODE_DICTATE
        }

        private val EligibleHostCodes: Set<Int> = EligibleHosts.map { it.keyData().code }.toSet()
    }

    object Serializer : PreferenceSerializer<QuickActionSecondActions> {
        override fun serialize(value: QuickActionSecondActions): String {
            return QuickActionJsonConfig.encodeToString(value)
        }

        override fun deserialize(value: String): QuickActionSecondActions {
            return runCatching {
                QuickActionJsonConfig.decodeFromString<QuickActionSecondActions>(value).sanitized()
            }.getOrDefault(Default)
        }
    }
}
