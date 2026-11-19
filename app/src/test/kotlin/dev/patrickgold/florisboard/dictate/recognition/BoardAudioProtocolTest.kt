package dev.patrickgold.florisboard.dictate.recognition

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardAudioProtocolTest {
    @Test fun onlyBrrBoardUidPackagesAreTrusted() {
        assertFalse(BoardAudioProtocol.trustedPackages(null))
        assertFalse(BoardAudioProtocol.trustedPackages(emptyList()))
        assertFalse(BoardAudioProtocol.trustedPackages(listOf("net.devemperor.dictate")))
        assertFalse(BoardAudioProtocol.trustedPackages(listOf("com.example.attacker")))
        assertTrue(BoardAudioProtocol.trustedPackages(listOf("com.mangoloads.board")))
    }

    @Test fun exactPcmFormatIsRequired() {
        assertTrue(BoardAudioProtocol.validFormat(16_000, 1, 2))
        assertFalse(BoardAudioProtocol.validFormat(44_100, 1, 2))
        assertFalse(BoardAudioProtocol.validFormat(16_000, 2, 2))
        assertFalse(BoardAudioProtocol.validFormat(16_000, 1, 3))
    }

    @Test fun rejectsEmptyOddTruncatedAndUnboundedAudio() {
        assertFalse(BoardAudioProtocol.validLength(0))
        assertFalse(BoardAudioProtocol.validLength(1))
        assertFalse(BoardAudioProtocol.validLength(3))
        assertTrue(BoardAudioProtocol.validLength(2))
        assertTrue(BoardAudioProtocol.validLength(3_840_000))
        assertFalse(BoardAudioProtocol.validLength(3_840_002))
    }
}
