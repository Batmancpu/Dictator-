/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.scan

/**
 * Turning a set of tapped lines into the one string that gets inserted (issue #390).
 *
 * **One printed line becomes one inserted line.** Always, with no cleverness in between.
 *
 * The tempting alternative is to tell a wrapped sentence from a deliberate line break — the
 * recogniser's blocks and the ragged right edge of an address make a heuristic look easy — and rejoin
 * the wrapped one with a space. It was written that way first and taken back out, because the two
 * mistakes it can make are not the same size. A sentence that arrives broken across two lines costs one
 * backspace. An address that arrives as `Erika Mustermann Heidestraße 17 51147 Köln` costs finding two
 * positions in a run-on string and pressing Enter in each. When a guess can only be wrong in one of two
 * directions, it should be wrong in the cheaper one — and a rule with no guess in it is also a rule the
 * user can predict, which in a keyboard is worth more than being right slightly more often.
 *
 * Order is always reading order, never tap order: tapping the town before the name must still insert the
 * name first, because what was selected is a region of the page, not a sequence of presses.
 */
object ScanSelection {

    fun join(scan: ScanText, selected: Set<Int>): String {
        if (selected.isEmpty()) return ""
        val indices = selected.filter { it in scan.lines.indices }.sorted()
        if (indices.isEmpty()) return ""
        return indices.joinToString("\n") { scan.lines[it].text.trim() }.trim()
    }

    /** Everything that was recognised, under the same rule. What the "select all" affordance inserts. */
    fun all(scan: ScanText): String = join(scan, scan.lines.indices.toSet())
}
