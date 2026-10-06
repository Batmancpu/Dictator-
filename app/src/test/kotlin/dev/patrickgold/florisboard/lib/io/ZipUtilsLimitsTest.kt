/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.lib.io

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The unpacking limits of #383: counted as the bytes come out, never believed from the archive. */
class ZipUtilsLimitsTest {
    private val work = Files.createTempDirectory("unzip-limits").toFile()

    @AfterTest
    fun cleanUp() {
        work.deleteRecursively()
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = File(work, "test.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `an entry over its limit is left out, and leaves no partial file behind`() {
        val small = "theme".toByteArray()
        val zip = archive("big.bin" to ByteArray(5_000), "small.json" to small)
        val out = File(work, "out")

        ZipUtils.unzip(zip, out, ZipUtils.UnzipLimits(maxEntryBytes = 1_000))

        assertFalse(File(out, "big.bin").exists())
        assertContentEquals(small, File(out, "small.json").readBytes())
    }

    @Test
    fun `an archive with more entries than allowed is refused`() {
        val zip = archive(*Array(5) { "f$it" to ByteArray(1) })

        assertFailsWith<ZipException> {
            ZipUtils.unzip(zip, File(work, "out"), ZipUtils.UnzipLimits(maxEntries = 3))
        }
    }

    // Zeros compress to almost nothing, which is what makes a small archive able to fill a device.
    @Test
    fun `unpacking stops before the device would be left without space`() {
        val zip = archive("zeros.bin" to ByteArray(9 * 1024 * 1024))
        val out = File(work, "out")

        assertFailsWith<IOException> {
            ZipUtils.unzip(zip, out, ZipUtils.UnzipLimits(minFreeBytes = Long.MAX_VALUE))
        }
        assertFalse(File(out, "zeros.bin").exists())
    }
}
