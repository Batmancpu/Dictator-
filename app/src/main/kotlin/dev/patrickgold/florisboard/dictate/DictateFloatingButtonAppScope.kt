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

/**
 * Which apps the floating dictation button is allowed to appear over (issue #392).
 *
 * The button used to follow its owner everywhere, which is two problems in one: banking and payment apps
 * react badly to anything drawn on top of them, and apps that carry a microphone of their own (or have no
 * text to dictate at all, like a launcher) get a second one for nothing.
 *
 * [ALL] is the default, so nothing changes for anyone who never opens the setting. The other two read the
 * same list of packages in opposite directions — which is the point: "where do I want this" and "where do
 * I not want this" are different questions, and which one is easier to answer depends on the person.
 */
enum class DictateFloatingButtonAppScope {
    /** Everywhere, i.e. no filter at all. */
    ALL,

    /** Only over the selected apps. Anything not named — including an app we cannot name — stays clear. */
    ONLY_SELECTED,

    /** Everywhere except the selected apps. */
    EXCEPT_SELECTED;
}
