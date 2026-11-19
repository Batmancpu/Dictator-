package dev.patrickgold.florisboard.dictate.recognition

/**
 * RecognitionService does not learn whether the remote editor is private. In particular an
 * external PCM pipe may be supplied by a keyboard that must not leak its editor contents.
 * Treat every externally supplied recording as sensitive and ephemeral by default.
 */
internal object ExternalAudioPrivacy {
    fun suppressHistory(explicitlySensitive: Boolean, hasExternalPcm: Boolean): Boolean =
        explicitlySensitive || hasExternalPcm

    fun mayRetainForRetry(isRecognitionService: Boolean, isSensitive: Boolean): Boolean =
        !(isRecognitionService && isSensitive)
}
