package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.AutomationProfileResponse
import app.spammy.hof.automation.dto.AutomationProfileMapRequest
import app.spammy.hof.automation.dto.AutomationProfileMapResponse
import app.spammy.hof.automation.dto.CreateAutomationProfileRequest
import app.spammy.hof.automation.dto.UpdateAutomationProfileRequest
import app.spammy.hof.automation.service.AutomationProfileService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class AutomationProfileControllerTest {
    private val profileService = Mockito.mock(AutomationProfileService::class.java)
    private val controller = AutomationProfileController(profileService)

    @Test
    fun listDelegatesToService() {
        Mockito.`when`(profileService.findAll(accountId = 1L)).thenReturn(listOf(profile()))

        val response = controller.findAll(accountId = 1L)

        assertEquals(1, response.size)
        assertEquals("새 자동전투", response.single().name)
        Mockito.verify(profileService).findAll(accountId = 1L)
    }

    @Test
    fun createAndUpdateAndDeleteDelegateToService() {
        val createRequest = CreateAutomationProfileRequest(
            name = "새 자동전투",
            mode = "TIME_BURN",
            maps = emptyList(),
        )
        val updateRequest = UpdateAutomationProfileRequest(
            name = "고급 던전",
            mode = "LIMITED_DUNGEON",
            maps = listOf(
                AutomationProfileMapRequest(
                    categoryId = "adventure_map",
                    mapCode = "Noble205",
                    partyPresetId = 9L,
                    executionOrder = 0,
                ),
            ),
            enabled = true,
        )
        Mockito.`when`(profileService.create(accountId = 1L, request = createRequest)).thenReturn(profile())
        Mockito.`when`(profileService.update(accountId = 1L, profileId = 3L, request = updateRequest))
            .thenReturn(profile(name = "고급 던전", mode = "LIMITED_DUNGEON"))

        assertEquals("새 자동전투", controller.create(accountId = 1L, request = createRequest).name)
        assertEquals("LIMITED_DUNGEON", controller.update(accountId = 1L, profileId = 3L, request = updateRequest).mode)
        controller.delete(accountId = 1L, profileId = 3L)

        Mockito.verify(profileService).delete(accountId = 1L, profileId = 3L)
    }

    private fun profile(
        name: String = "새 자동전투",
        mode: String = "TIME_BURN",
    ): AutomationProfileResponse =
        AutomationProfileResponse(
            id = 3L,
            accountId = 1L,
            name = name,
            mode = mode,
            maps = listOf(
                AutomationProfileMapResponse(
                    categoryId = "adventure_map",
                    mapCode = "Noble205",
                    partyPresetId = 9L,
                    executionOrder = 0,
                ),
            ),
            enabled = true,
            createdAt = "2026-07-10T00:00:00Z",
            updatedAt = "2026-07-10T00:00:00Z",
        )
}
