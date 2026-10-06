/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/**
 * Whether a Uri that another app handed to one of our exported screens may be opened (#383).
 *
 * Those screens open what they are given with this app's own identity. A `file://` Uri is read with our
 * file permissions, our private files included, and a `content://` Uri of our own providers reads our
 * own data. Neither is something another app can honestly share: a real share is the sharer's
 * `content://` Uri with a read grant. Any explicit intent reaches these screens, intent filter or not,
 * which is why the check is on the Uri and not on the action.
 */
fun Context.acceptsSharedUri(uri: Uri): Boolean {
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
    val authority = uri.authority ?: return false
    return authority != packageName && !authority.startsWith("$packageName.")
}
