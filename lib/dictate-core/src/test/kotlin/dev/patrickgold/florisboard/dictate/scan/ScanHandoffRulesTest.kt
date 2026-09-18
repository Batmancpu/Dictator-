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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Whether a scan lying in the cache still belongs to the field in front of us (issue #390).
 *
 * The rule these tests pin down is that **only age throws anything away**. Both of the other answers
 * wait, and both had to be learned the hard way: a blank editor info on the way back from the camera,
 * and a trip through the camera that lands the user in a different app before they find their way back
 * to the field they started in. Both used to delete the photo.
 */
class ScanHandoffRulesTest : FunSpec({

    val now = 1_800_000_000_000L
    fun result(ageMs: Long, pkg: String? = "com.example.bank") =
        ScanResult(ScanStatus.CAPTURED, sourcePackage = pkg, capturedAtMs = now - ageMs)

    test("a fresh scan in the same app is taken") {
        ScanHandoffRules.verdict(result(3_000), now, "com.example.bank") shouldBe ScanVerdict.ACCEPT
    }

    test("the wrong app waits, it does not destroy the photo") {
        // It is never *shown* here — that is what ACCEPT would mean. It simply stays in the cache, so
        // that walking back to the field it was taken for still finds it.
        ScanHandoffRules.verdict(result(3_000), now, "com.example.messenger") shouldBe ScanVerdict.DEFER
    }

    test("not knowing which app we are in waits too") {
        // On the way back from the camera the window can be shown before the editor is reattached, and
        // the editor info is then blank rather than wrong.
        ScanHandoffRules.verdict(result(3_000), now, null) shouldBe ScanVerdict.DEFER
        // A scan that never named an app has nothing to wait for.
        ScanHandoffRules.verdict(result(3_000, pkg = null), now, null) shouldBe ScanVerdict.ACCEPT
    }

    test("a scan older than two minutes is an intention that has been spent") {
        ScanHandoffRules.verdict(result(119_000), now, "com.example.bank") shouldBe ScanVerdict.ACCEPT
        ScanHandoffRules.verdict(result(121_000), now, "com.example.bank") shouldBe ScanVerdict.DISCARD
        // Age beats everything: an old scan is not worth waiting for, in any app or none.
        ScanHandoffRules.verdict(result(121_000), now, null) shouldBe ScanVerdict.DISCARD
        ScanHandoffRules.verdict(result(121_000), now, "com.example.messenger") shouldBe ScanVerdict.DISCARD
    }

    test("a note from the future fails closed") {
        ScanHandoffRules.verdict(result(-600_000), now, "com.example.bank") shouldBe ScanVerdict.DISCARD
    }

    test("a note with no timestamp is not a note") {
        ScanHandoffRules.verdict(ScanResult(), now, "com.example.bank") shouldBe ScanVerdict.DISCARD
    }

    test("the note survives a JSON round trip") {
        val original = result(1_000)
        val json = ScanResult.Json.encodeToString(ScanResult.serializer(), original)
        ScanResult.Json.decodeFromString(ScanResult.serializer(), json) shouldBe original
    }

    test("a note written before an app update does not throw after it") {
        // The photo is taken by one version and read by the next; an unknown status has to degrade to
        // FAILED rather than take the keyboard down on the way back into the field.
        val fromTheFuture = """{"status":"SOMETHING_NEW","sourcePackage":"com.example.bank","capturedAtMs":1}"""
        ScanResult.Json.decodeFromString(ScanResult.serializer(), fromTheFuture).status shouldBe ScanStatus.FAILED
        val withExtraField = """{"status":"CAPTURED","capturedAtMs":1,"somethingElse":true}"""
        ScanResult.Json.decodeFromString(ScanResult.serializer(), withExtraField).status shouldBe ScanStatus.CAPTURED
    }
})
