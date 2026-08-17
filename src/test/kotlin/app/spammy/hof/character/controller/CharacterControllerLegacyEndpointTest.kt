package app.spammy.hof.character.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.character.service.CharacterSyncJobService
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.character.service.CharacterOperationJobService
import app.spammy.hof.character.command.CharacterCommandExecutor
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.transfer.CharacterTransferService
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class CharacterControllerLegacyEndpointTest {
    @Test
    fun controllerDoesNotExposeBlockingCharacterSyncEndpoints() {
        val controller = CharacterController(
            characterService = Mockito.mock(CharacterService::class.java),
            characterSyncJobService = Mockito.mock(CharacterSyncJobService::class.java),
            characterPatternService = Mockito.mock(CharacterPatternService::class.java),
            characterSnapshotSynchronizer = Mockito.mock(CharacterSnapshotSynchronizer::class.java),
            characterOperationJobService = Mockito.mock(CharacterOperationJobService::class.java),
            characterCommandExecutor = Mockito.mock(CharacterCommandExecutor::class.java),
            characterLifecycleService = Mockito.mock(CharacterLifecycleService::class.java),
            characterTransferService = Mockito.mock(CharacterTransferService::class.java),
            sessionRecoveryService = Mockito.mock(HofSessionRecoveryService::class.java),
        )
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        listOf("/sync", "/sync-if-needed").forEach { legacyPath ->
            mockMvc.perform(post("/api/characters$legacyPath"))
                .andExpect(status().isNotFound)
        }
        mockMvc.perform(get("/api/characters/legacy-id/management"))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/characters/legacy-id/management/actions"))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/api/characters/legacy-id"))
            .andExpect(status().isNotFound)
    }
}
