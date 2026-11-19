package dev.patrickgold.florisboard.dictate.recognition

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import dev.patrickgold.florisboard.FlorisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Explicit, UI-free brrBoard recording receiver.
 *
 * Messenger's system-populated Message.sendingUid is checked before accepting any descriptor.
 * The caller transfers one READ-ONLY file descriptor, never editor text. The only successful
 * response contains final transcription text; no partial results, history, or clipboard data.
 * This service never opens a microphone. RecognitionSession consumes the supplied PCM through
 * the existing DictateController provider/settings/post-processing pipeline.
 */
class BoardRecordedAudioService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!authorizedCaller(message.sendingUid)) {
                audioFrom(message.data)?.closeQuietly()
                return
            }
            when (message.what) {
                BoardAudioProtocol.REQUEST -> begin(message)
                BoardAudioProtocol.CANCEL -> {
                    val current = active
                    if (current != null &&
                        current.sessionId == message.data.getLong(BoardAudioProtocol.SESSION, -1L)) {
                        finish(current, null, 0, cancelEngine = true)
                    }
                }
                else -> audioFrom(message.data)?.closeQuietly()
            }
        }
    })
    private var active: Active? = null

    private class Active(
        val sessionId: Long,
        val audio: ParcelFileDescriptor,
        val caller: Messenger,
    ) {
        var session: RecognitionSession? = null
        var loading: Job? = null
        var death: IBinder.DeathRecipient? = null
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        active?.let { finish(it, null, 0, cancelEngine = true) }
        return false
    }

    override fun onDestroy() {
        active?.let { finish(it, null, 0, cancelEngine = true) }
        scope.cancel()
        super.onDestroy()
    }

    private fun authorizedCaller(uid: Int): Boolean {
        if (uid < 0) return false
        // PackageManager maps the kernel-verified Binder sender UID, not a caller-supplied name.
        // Different release certificates deliberately do NOT require a shared signature key.
        return BoardAudioProtocol.trustedPackages(packageManager.getPackagesForUid(uid)?.toList())
    }

    private fun begin(message: Message) {
        val bundle = message.data
        bundle.classLoader = ParcelFileDescriptor::class.java.classLoader
        val audio = audioFrom(bundle)
        val caller = message.replyTo
        val sessionId = bundle.getLong(BoardAudioProtocol.SESSION, -1L)
        val bytes = bundle.getLong(BoardAudioProtocol.BYTES, -1L)
        val valid = audio != null && caller != null && sessionId > 0 &&
            BoardAudioProtocol.validFormat(
                bundle.getInt(BoardAudioProtocol.RATE, -1),
                bundle.getInt(BoardAudioProtocol.CHANNELS, -1),
                bundle.getInt(BoardAudioProtocol.ENCODING, -1),
            ) && BoardAudioProtocol.validLength(bytes) && audio.statSize == bytes
        if (!valid) {
            audio?.closeQuietly()
            send(caller, BoardAudioProtocol.ERROR, sessionId, BoardAudioProtocol.ERROR_FORMAT)
            return
        }
        if (active != null || RecognitionBridge.isActive()) {
            audio?.closeQuietly()
            send(caller, BoardAudioProtocol.ERROR, sessionId, BoardAudioProtocol.ERROR_BUSY)
            return
        }
        val request = Active(sessionId, audio!!, caller!!)
        active = request
        val death = IBinder.DeathRecipient {
            main.post {
                if (active === request) finish(request, null, 0, cancelEngine = true)
            }
        }
        request.death = death
        try {
            caller.binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            finish(request, null, 0, cancelEngine = true)
            return
        }
        request.loading = scope.launch {
            val app = application as FlorisApplication
            val ready = withTimeoutOrNull(PREFERENCES_TIMEOUT_MS) {
                app.preferenceStoreLoaded.first { it }
            } != null
            if (!isActive || active !== request) return@launch
            request.loading = null
            if (!ready) {
                finish(request, null, BoardAudioProtocol.ERROR_SETTINGS)
                return@launch
            }
            try {
                val host = object : RecognitionSession.Host {
                    override fun onResults(text: String) {
                        if (active === request) finish(request, text, 0)
                    }
                    override fun onError(code: Int) {
                        Log.w(TAG, "recorded PCM recognition failed, engineCode=" + code)
                        if (active === request) finish(request, null, BoardAudioProtocol.ERROR_ENGINE)
                    }
                }
                val engine = RecognitionSession(
                    applicationContext,
                    host,
                    sensitiveField = true,
                    externalAudio = request.audio,
                )
                request.session = engine
                engine.start()
            } catch (e: Exception) {
                Log.w(TAG, "recorded PCM receiver startup failure: " + e.javaClass.simpleName)
                if (active === request) finish(request, null, BoardAudioProtocol.ERROR_ENGINE)
            }
        }
    }

    private fun finish(
        request: Active,
        result: String?,
        error: Int,
        cancelEngine: Boolean = false,
    ) {
        if (active !== request) return
        active = null
        request.loading?.cancel()
        request.loading = null
        request.session?.cancel()
        request.session = null
        request.death?.let { runCatching { request.caller.binder.unlinkToDeath(it, 0) } }
        request.audio.closeQuietly()
        if (result != null) {
            send(request.caller, BoardAudioProtocol.RESULT, request.sessionId, 0, result)
        } else if (!cancelEngine) {
            send(request.caller, BoardAudioProtocol.ERROR, request.sessionId, error)
        }
    }

    private fun audioFrom(bundle: Bundle?): ParcelFileDescriptor? = runCatching {
        if (bundle == null) null
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(BoardAudioProtocol.AUDIO, ParcelFileDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable<ParcelFileDescriptor>(BoardAudioProtocol.AUDIO)
        }
    }.getOrNull()

    private fun send(reply: Messenger?, what: Int, id: Long, error: Int, text: String? = null) {
        if (reply == null) return
        val response = Message.obtain().apply {
            this.what = what
            data = Bundle().apply {
                putLong(BoardAudioProtocol.SESSION, id)
                if (what == BoardAudioProtocol.RESULT) putString(BoardAudioProtocol.TEXT, text.orEmpty())
                else putInt(BoardAudioProtocol.ERROR_CODE, error)
            }
        }
        runCatching { reply.send(response) }
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    private companion object {
        const val TAG = "BoardAudioReceiver"
        const val PREFERENCES_TIMEOUT_MS = 15_000L
    }
}

/** Versioned Binder/Messenger wire contract; raw 16-kHz mono PCM16, max 120 seconds. */
internal object BoardAudioProtocol {
    const val BOARD_PACKAGE = "com.mangoloads.board"
    const val REQUEST = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val ERROR = 4
    const val SESSION = "session"
    const val AUDIO = "audio"
    const val BYTES = "bytes"
    const val RATE = "rate"
    const val CHANNELS = "channels"
    const val ENCODING = "encoding"
    const val TEXT = "text"
    const val ERROR_CODE = "error"
    const val ERROR_FORMAT = 1
    const val ERROR_BUSY = 2
    const val ERROR_SETTINGS = 3
    const val ERROR_ENGINE = 4
    const val SAMPLE_RATE = 16_000
    const val PCM16_ENCODING = 2
    const val MAX_BYTES = 3_840_000L

    fun trustedPackages(packages: List<String>?): Boolean = packages?.contains(BOARD_PACKAGE) == true
    fun validFormat(rate: Int, channels: Int, encoding: Int): Boolean =
        rate == SAMPLE_RATE && channels == 1 && encoding == PCM16_ENCODING
    fun validLength(length: Long): Boolean = length >= 2L && length <= MAX_BYTES && length % 2L == 0L
}
