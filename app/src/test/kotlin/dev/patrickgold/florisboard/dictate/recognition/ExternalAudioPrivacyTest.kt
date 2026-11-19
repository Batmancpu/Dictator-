package dev.patrickgold.florisboard.dictate.recognition

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalAudioPrivacyTest {
    @Test fun externalPcmIsAlwaysPrivateEvenWithoutEditorMetadata() {
        assertTrue(ExternalAudioPrivacy.suppressHistory(false, true))
        assertTrue(ExternalAudioPrivacy.suppressHistory(true, false))
        assertFalse(ExternalAudioPrivacy.suppressHistory(false, false))
    }

    @Test fun sensitiveRecognitionRequestsCannotRetainFailedAudio() {
        assertFalse(ExternalAudioPrivacy.mayRetainForRetry(true, true))
        assertTrue(ExternalAudioPrivacy.mayRetainForRetry(true, false))
        assertTrue(ExternalAudioPrivacy.mayRetainForRetry(false, true))
    }
}
