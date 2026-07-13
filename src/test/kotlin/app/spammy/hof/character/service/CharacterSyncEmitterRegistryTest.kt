package app.spammy.hof.character.service

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterSyncEmitterRegistryTest {
    @Test
    fun registerCreatesEmitterWithNonImmediateTimeoutForStreaming() {
        val emitter = CharacterSyncEmitterRegistry().register(jobId = 1L)
        val timeout = assertNotNull(emitter.timeout)

        assertTrue(timeout > 1_000L)
    }
}
