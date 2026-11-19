package dev.patrickgold.florisboard.dictate.recognition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardAudioWakeContractTest {
    @Test fun stableExplicitAuthorityAndNoDataMethod() {
        assertEquals("com.mangoloads.speak.boardaudio", BoardAudioWakeContract.AUTHORITY)
        assertEquals("prepare-recorded-audio", BoardAudioWakeContract.METHOD)
        assertEquals("ready", BoardAudioWakeContract.READY)
    }

    @Test fun callerIdentityCannotBeAnArbitraryPackage() {
        assertTrue(BoardAudioProtocol.trustedPackages(listOf("com.mangoloads.board")))
        assertFalse(BoardAudioProtocol.trustedPackages(listOf("com.attacker.ime")))
        assertFalse(BoardAudioProtocol.trustedPackages(null))
    }
}
