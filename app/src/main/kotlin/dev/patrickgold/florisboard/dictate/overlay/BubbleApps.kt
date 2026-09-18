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
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.jetpref.datastore.model.PreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The apps the floating button's visibility is filtered by (issue #392), as they are written to
 * preferences, together with the rule that reads them.
 *
 * One list serves both directions — [DictateFloatingButtonAppScope.ONLY_SELECTED] and
 * [DictateFloatingButtonAppScope.EXCEPT_SELECTED] — so switching the mode keeps the selection instead of
 * silently starting over. Unlike [BubbleAnchors] this list has no cap and needs none: every entry is
 * something a person ticked by hand, and nothing writes to it in the background.
 */
@Serializable
data class BubbleApps(
    val packages: List<String> = emptyList(),
) {
    /** The selection as a set, which is how [allows] wants it. */
    fun toSet(): Set<String> = packages.toSet()

    /** [pkg] with its tick flipped, keeping the list sorted so the stored form does not depend on order. */
    fun toggled(pkg: String): BubbleApps = if (pkg in packages) {
        BubbleApps(packages - pkg)
    } else {
        BubbleApps((packages + pkg).sorted())
    }

    object Serializer : PreferenceSerializer<BubbleApps> {
        override fun serialize(value: BubbleApps): String {
            return Json.encodeToString(value)
        }

        override fun deserialize(value: String): BubbleApps {
            return try {
                Json.decodeFromString(value)
            } catch (e: Throwable) {
                flogError { "Failed to deserialize BubbleApps: ${e.message}" }
                Empty
            }
        }
    }

    companion object {
        val Empty = BubbleApps()

        /**
         * Whether the button may be drawn over [pkg] under [scope] and [selected].
         *
         * [pkg] is null when the foreground app could not be read at all — which is not a theoretical
         * case here, since an app hardened enough to dislike overlays is also the kind that hides its
         * nodes from a service that is not registered as an accessibility tool. **An unknown package
         * fails in the direction of its own mode:** an allow-list that cannot name the app in front has
         * not allowed it, and a block-list that cannot name it has not blocked it. Anything else would
         * make one of the two settings quietly mean its opposite in the apps it was bought for.
         */
        fun allows(
            scope: DictateFloatingButtonAppScope,
            selected: Set<String>,
            pkg: String?,
        ): Boolean = when (scope) {
            DictateFloatingButtonAppScope.ALL -> true
            DictateFloatingButtonAppScope.ONLY_SELECTED -> pkg != null && pkg in selected
            DictateFloatingButtonAppScope.EXCEPT_SELECTED -> pkg == null || pkg !in selected
        }
    }
}
