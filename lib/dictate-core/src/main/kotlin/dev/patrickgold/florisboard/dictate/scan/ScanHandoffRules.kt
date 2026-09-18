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

import kotlin.math.abs

/** What to do with a scan that is lying in the cache. */
enum class ScanVerdict {
    /** It belongs here: claim it and show it. */
    ACCEPT,

    /** It cannot be judged yet. Leave it exactly where it is and ask again on the next hook. */
    DEFER,

    /** It is too old to still be wanted. Throw it away. */
    DISCARD,
}

/**
 * Whether a scan that is lying in the cache still belongs to the field the keyboard is now attached to
 * (issue #390).
 *
 * The keyboard hands the camera the foreground and may not get it back for a while, or at all, or not
 * in the same app. Two questions decide it:
 *
 * - **Is it still fresh?** The round trip is seconds. A note from ten minutes ago is an intention that
 *   has been spent, the same reasoning [dev.patrickgold.florisboard.dictate.InstantRecordingArm] applies
 *   to its own flag.
 * - **Is it the same app?** A scan started in a banking app must not open in WhatsApp. The package name
 *   is the only field identity that survives a process death intact, which is exactly the case this has
 *   to hold up in.
 *
 * **Only age discards. Everything else waits.** Both of the other answers — "this is a different app"
 * and "I do not know yet which app this is" — are [DEFER], and that took two rounds of getting it wrong
 * to arrive at. A blank editor info is routine on the way back from the camera, where the window can be
 * shown before the editor is reattached; and a *different* app is routine too, because the trip through
 * a camera can drop the user somewhere else entirely before they find their way back to the field they
 * started in. Deleting the photo in either case destroys something the user just made, seconds before it
 * would have been wanted. Waiting costs a file in the cache for at most [MAX_AGE_MS]; the scan is still
 * never *shown* anywhere but in the app it was taken for, which is the guarantee that actually matters.
 *
 * There is a third guarantee that is not a rule but a shape: nothing here ever *inserts*. The worst a
 * wrong answer can do is open a panel. Nobody should later "improve" this into an auto-insert.
 */
object ScanHandoffRules {

    const val MAX_AGE_MS: Long = 2 * 60 * 1000L

    fun verdict(result: ScanResult, nowMs: Long, currentPackage: String?): ScanVerdict {
        if (result.capturedAtMs <= 0L) return ScanVerdict.DISCARD
        // abs(), because a device whose clock moved between writing and reading should fail closed
        // rather than hand out a result that claims to be from the future.
        if (abs(nowMs - result.capturedAtMs) > MAX_AGE_MS) return ScanVerdict.DISCARD
        val source = result.sourcePackage ?: return ScanVerdict.ACCEPT
        return if (source == currentPackage) ScanVerdict.ACCEPT else ScanVerdict.DEFER
    }
}
