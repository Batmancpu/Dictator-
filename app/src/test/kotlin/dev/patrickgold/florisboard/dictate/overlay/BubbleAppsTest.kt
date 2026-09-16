/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import dev.patrickgold.florisboard.dictate.DictateFloatingButtonAppScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-app visibility filter for the floating button (issue #392).
 *
 * The case worth having a test for is the last one: an app hardened enough to dislike overlays is also
 * the kind that hides its accessibility nodes, so "which app is this" can come back with no answer at
 * all — in exactly the apps the filter was set up for.
 */
class BubbleAppsTest {

    private val selected = setOf("com.whatsapp", "com.android.chrome")

    private fun allows(scope: DictateFloatingButtonAppScope, pkg: String?) =
        BubbleApps.allows(scope, selected, pkg)

    @Test
    fun `no filter lets everything through`() {
        assertTrue(allows(DictateFloatingButtonAppScope.ALL, "com.whatsapp"))
        assertTrue(allows(DictateFloatingButtonAppScope.ALL, "com.some.bank"))
        assertTrue(allows(DictateFloatingButtonAppScope.ALL, null))
    }

    @Test
    fun `an allow-list shows only what it names`() {
        assertTrue(allows(DictateFloatingButtonAppScope.ONLY_SELECTED, "com.whatsapp"))
        assertTrue(allows(DictateFloatingButtonAppScope.ONLY_SELECTED, "com.android.chrome"))
        assertFalse(allows(DictateFloatingButtonAppScope.ONLY_SELECTED, "com.some.bank"))
    }

    @Test
    fun `a block-list hides only what it names`() {
        assertFalse(allows(DictateFloatingButtonAppScope.EXCEPT_SELECTED, "com.whatsapp"))
        assertTrue(allows(DictateFloatingButtonAppScope.EXCEPT_SELECTED, "com.some.bank"))
    }

    /** An empty selection means each mode's own extreme, not "everywhere" for both. */
    @Test
    fun `an empty list is read as what it says`() {
        assertFalse(BubbleApps.allows(DictateFloatingButtonAppScope.ONLY_SELECTED, emptySet(), "com.whatsapp"))
        assertTrue(BubbleApps.allows(DictateFloatingButtonAppScope.EXCEPT_SELECTED, emptySet(), "com.whatsapp"))
    }

    @Test
    fun `an unknown app fails in the direction of its own mode`() {
        assertFalse(allows(DictateFloatingButtonAppScope.ONLY_SELECTED, null))
        assertTrue(allows(DictateFloatingButtonAppScope.EXCEPT_SELECTED, null))
    }

    @Test
    fun `toggling adds and removes, and the stored order does not depend on the tapping order`() {
        val one = BubbleApps.Empty.toggled("com.b").toggled("com.a")
        val other = BubbleApps.Empty.toggled("com.a").toggled("com.b")
        assertEquals(listOf("com.a", "com.b"), one.packages)
        assertEquals(one, other)
        assertEquals(BubbleApps.Empty, one.toggled("com.a").toggled("com.b"))
    }

    @Test
    fun `a round trip through preferences keeps the selection`() {
        val apps = BubbleApps(listOf("com.whatsapp", "org.telegram.messenger"))
        val restored = BubbleApps.Serializer.deserialize(BubbleApps.Serializer.serialize(apps))
        assertEquals(apps, restored)
    }

    /** A preference file someone has edited by hand must not take the button away for good. */
    @Test
    fun `unreadable storage falls back to an empty selection`() {
        assertEquals(BubbleApps.Empty, BubbleApps.Serializer.deserialize("{not json"))
    }
}
