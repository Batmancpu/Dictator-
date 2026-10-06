/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.lib.io

import android.content.Context
import android.net.Uri
import org.florisboard.lib.android.copyRecursively
import org.florisboard.lib.android.write
import org.florisboard.lib.kotlin.io.FsDir
import org.florisboard.lib.kotlin.io.FsFile
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ZipUtils {
    fun readFileFromArchive(context: Context, zipRef: FlorisRef, relPath: String) = runCatching<String> {
        when {
            zipRef.isAssets -> {
                zipRef.subRef(relPath).loadTextAsset(context).getOrThrow()
            }
            zipRef.isCache || zipRef.isInternal -> {
                val flexHandle = FsFile(zipRef.absolutePath(context))
                check(flexHandle.isFile) { "Given ref $zipRef is not a file!" }
                var fileContents: String? = null
                ZipFile(flexHandle).use { flexFile ->
                    flexFile.getEntry(relPath)?.let { flexEntry ->
                        fileContents = flexFile.getInputStream(flexEntry).bufferedReader().use { it.readText() }
                    }
                }
                fileContents ?: error("Failed to load requested file $relPath")
            }
            else -> error("Unsupported source!")
        }
    }

    fun zip(context: Context, srcRef: FlorisRef, dstRef: FlorisRef) =
        zip(context, FsDir(srcRef.absolutePath(context)), dstRef)

    fun zip(context: Context, srcDir: FsDir, dstRef: FlorisRef) = runCatching {
        check(srcDir.exists() && srcDir.isDirectory) { "Cannot zip standalone file." }
        when {
            dstRef.isCache || dstRef.isInternal -> {
                val flexFile = FsFile(dstRef.absolutePath(context))
                flexFile.parentFile?.mkdirs()
                flexFile.delete()
                FileOutputStream(flexFile).use { fileOut ->
                    ZipOutputStream(fileOut).use { zipOut ->
                        zip(srcDir, zipOut, "")
                    }
                }
            }
            else -> error("Unsupported destination!")
        }
    }

    fun zip(srcDir: FsDir, dstFile: FsFile) {
        check(srcDir.exists() && srcDir.isDirectory) { "Cannot zip standalone file." }
        dstFile.parentFile?.mkdirs()
        dstFile.delete()
        FileOutputStream(dstFile).use { outStream ->
            ZipOutputStream(outStream).use { zipOut ->
                zip(srcDir, zipOut, "")
            }
        }
    }

    fun zip(context: Context, srcDir: FsDir, uri: Uri) = runCatching {
        check(srcDir.exists() && srcDir.isDirectory) { "Cannot zip standalone file." }
        context.contentResolver.write(uri) { fileOut ->
            ZipOutputStream(fileOut).use { zipOut ->
                zip(srcDir, zipOut, "")
            }
        }
    }

    internal fun zip(srcDir: FsDir, zipOut: ZipOutputStream, base: String) {
        val dir = FsDir(srcDir, base)
        for (file in dir.listFiles() ?: arrayOf()) {
            val path = if (base.isBlank()) file.name else "$base/${file.name}"
            if (file.isDirectory) {
                zipOut.putNextEntry(ZipEntry("$path/"))
                zipOut.closeEntry()
                zip(srcDir, zipOut, path)
            } else {
                zipOut.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }
        }
    }

    fun unzip(context: Context, srcRef: FlorisRef, dstRef: FlorisRef) =
        unzip(context, srcRef, FsDir(dstRef.absolutePath(context)))

    fun unzip(context: Context, srcRef: FlorisRef, dstDir: FsFile) = runCatching {
        check(dstDir.exists() && dstDir.isDirectory) { "Cannot unzip into file." }
        dstDir.mkdirs()
        when {
            srcRef.isAssets -> {
                context.assets.copyRecursively(srcRef.relativePath.removeSuffix("/"), dstDir)
            }
            srcRef.isCache || srcRef.isInternal -> {
                val flexHandle = FsFile(srcRef.absolutePath(context))
                unzip(srcFile = flexHandle, dstDir = dstDir)
            }
            else -> error("Unsupported source!")
        }
    }

    /**
     * Unzips a given Zip file to the destination directory.
     *
     * @param srcFile The source Zip file handle.
     * @param dstDir The destination directory where the [srcFile] contents should be unzipped to.
     *
     * @throws IllegalArgumentException If the given [srcFile] is not existing on the file system or if it points to
     *  a directory instead.
     * @throws java.lang.SecurityException If the current file system does not permit an action.
     * @throws java.util.zip.ZipException If a Zip format error has occurred.
     * @throws java.io.IOException If an I/O error has occurred.
     */
    fun unzip(srcFile: FsFile, dstDir: FsDir, limits: UnzipLimits = UnzipLimits()) {
        require(srcFile.exists() && srcFile.isFile) { "Given src file `$srcFile` is not valid or a directory." }
        dstDir.mkdirs()
        ZipFile(srcFile).use { flexFile ->
            val flexEntries = flexFile.entries()
            var entryCount = 0
            while (flexEntries.hasMoreElements()) {
                val flexEntry = flexEntries.nextElement()
                if (++entryCount > limits.maxEntries) {
                    throw ZipException("More than ${limits.maxEntries} entries")
                }
                if (flexEntry.name.length > 255) {
                    continue
                }
                val flexEntryFile = FsFile(dstDir, flexEntry.name)
                val canonicalDestinationDirPath = dstDir.canonicalPath
                val canonicalDestinationFilePath = flexEntryFile.canonicalPath
                if (canonicalDestinationFilePath.length > 1023) {
                    continue
                }
                if (!canonicalDestinationFilePath.startsWith(canonicalDestinationDirPath + FsFile.separator)) {
                    continue
                }
                if (flexEntry.isDirectory) {
                    flexEntryFile.mkdir()
                } else {
                    flexFile.copy(flexEntry, flexEntryFile, dstDir, limits)
                }
            }
        }
    }

    /**
     * What an archive may unpack to, counted as the bytes come out rather than believed from its
     * directory, which says whatever the archive's maker wrote there and -1 when it does not know (#383).
     *
     * There is deliberately no cap on the total: a backup of the dictation history with its audio is
     * legitimately as large as the user's own audio budget. The device is protected instead, by stopping
     * before it is left with less than [minFreeBytes].
     */
    data class UnzipLimits(
        val maxEntryBytes: Long = 100_000_000L,
        val maxEntries: Int = 100_000,
        val minFreeBytes: Long = 200_000_000L,
    )

    private fun ZipFile.copy(srcEntry: ZipEntry, dstFile: FsFile, dstDir: FsDir, limits: UnzipLimits) {
        var written = 0L
        var fits = true
        dstFile.outputStream().use { outStream ->
            this.getInputStream(srcEntry).use { inStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var sinceSpaceCheck = 0L
                while (true) {
                    val read = inStream.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > limits.maxEntryBytes) {
                        fits = false
                        break
                    }
                    sinceSpaceCheck += read
                    if (sinceSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
                        sinceSpaceCheck = 0L
                        if (dstDir.usableSpace < limits.minFreeBytes) {
                            fits = false
                            break
                        }
                    }
                    outStream.write(buffer, 0, read)
                }
            }
        }
        // An entry over its own limit is left out, as before; one that would fill the device ends the
        // whole archive, since every entry after it would do the same.
        if (!fits) {
            dstFile.delete()
            if (written <= limits.maxEntryBytes) throw IOException("Not enough free space to unpack")
        }
    }

    private const val SPACE_CHECK_INTERVAL_BYTES = 8L * 1024 * 1024
}
