package app.spammy.hof.character.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.character.service.CharacterSyncJobService
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class CharacterControllerLegacyEndpointTest {
    @Test
    fun controllerDoesNotExposeBlockingCharacterSyncEndpoints() {
        val controller = CharacterController(
            characterService = Mockito.mock(CharacterService::class.java),
            characterSyncJobService = Mockito.mock(CharacterSyncJobService::class.java),
            characterPatternService = Mockito.mock(CharacterPatternService::class.java),
            sessionRecoveryService = Mockito.mock(HofSessionRecoveryService::class.java),
        )
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        listOf("/sync", "/sync-if-needed").forEach { legacyPath ->
            mockMvc.perform(post("/api/characters$legacyPath"))
                .andExpect(status().isMethodNotAllowed)
        }
    }
}
