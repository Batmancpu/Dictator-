package dev.patrickgold.florisboard.dictate.recognition

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * One-shot, UI-free cold-start entry point for the foreground brrBoard IME.
 *
 * Some OEMs decline to create a stopped app's exported bound service directly. Android's
 * ContentResolver creates the provider process on demand; after this authenticated no-data
 * handshake brrBoard binds the existing BoardRecordedAudioService normally. No microphone,
 * audio, editor text, permissions, storage or transcription is accessed here.
 *
 * Never turn this into a general-purpose exported content/file provider.
 */
class BoardAudioWakeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val trusted = BoardAudioProtocol.trustedPackages(
            context?.packageManager?.getPackagesForUid(Binder.getCallingUid())?.toList(),
        )
        if (!trusted) throw SecurityException("Untrusted brrBoard caller")
        if (method != BoardAudioWakeContract.METHOD) {
            throw IllegalArgumentException("Unsupported method")
        }
        return Bundle().apply { putBoolean(BoardAudioWakeContract.READY, true) }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = throw SecurityException("No query interface")

    override fun getType(uri: Uri): String? = throw SecurityException("No MIME interface")
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("No insert interface")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw SecurityException("No delete interface")
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?,
    ): Int = throw SecurityException("No update interface")
}

internal object BoardAudioWakeContract {
    const val AUTHORITY = "com.mangoloads.speak.boardaudio"
    const val METHOD = "prepare-recorded-audio"
    const val READY = "ready"
}
