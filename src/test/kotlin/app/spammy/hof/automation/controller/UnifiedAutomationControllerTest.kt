package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.KeyQuestSettingsRequest
import app.spammy.hof.automation.dto.NormalQuestSettingsRequest
import app.spammy.hof.automation.dto.TimeSettingsRequest
import app.spammy.hof.automation.dto.ToggleModuleRequest
import app.spammy.hof.automation.dto.UnifiedAutomationSettingsRequest
import app.spammy.hof.automation.dto.UnifiedAutomationStatusResponse
import app.spammy.hof.automation.service.UnifiedAutomationService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class UnifiedAutomationControllerTest {
    private val service = Mockito.mock(UnifiedAutomationService::class.java)
    private val controller = UnifiedAutomationController(service)

    @Test
    fun delegatesSettingsAndLifecycleForTheAuthenticatedAccount() {
        val settings = settings()
        Mockito.`when`(service.get(7L)).thenReturn(status("PAUSED", settings))
        Mockito.`when`(service.update(7L, settings)).thenReturn(status(null, settings))
        Mockito.`when`(service.start(7L)).thenReturn(status("RUNNING", settings))
        Mockito.`when`(service.pause(7L)).thenReturn(status("PAUSED", settings))
        Mockito.`when`(service.resume(7L)).thenReturn(status("RUNNING", settings))
        Mockito.`when`(service.stop(7L)).thenReturn(status("CANCELLED", settings))

        assertEquals("PAUSED", controller.get(7L).job?.status)
        assertEquals(90, controller.update(7L, settings).settings.time.thresholdPercent)
        assertEquals("RUNNING", controller.start(7L).job?.status)
        assertEquals("PAUSED", controller.pause(7L).job?.status)
        assertEquals("RUNNING", controller.resume(7L).job?.status)
        assertEquals("CANCELLED", controller.stop(7L).job?.status)
    }

    private fun settings() = UnifiedAutomationSettingsRequest(
        keyQuest = KeyQuestSettingsRequest(enabled = true),
        time = TimeSettingsRequest(enabled = true, thresholdPercent = 90),
        cooldownAdventure = ToggleModuleRequest(enabled = true),
        dailyAdventure = ToggleModuleRequest(enabled = true),
        union = ToggleModuleRequest(enabled = true),
        normalQuest = NormalQuestSettingsRequest(enabled = false),
    )

    private fun status(
        jobStatus: String?,
        settings: UnifiedAutomationSettingsRequest,
    ) = UnifiedAutomationStatusResponse(
        profileId = 3L,
        job = jobStatus?.let {
            app.spammy.hof.automation.dto.AutomationJobResponse(
                id = 5L,
                accountId = 7L,
                profileId = 3L,
                status = it,
                currentStepIndex = 0,
                message = null,
                createdAt = "2026-07-13T00:00:00Z",
                startedAt = null,
                updatedAt = "2026-07-13T00:00:00Z",
                finishedAt = null,
            )
        },
        settings = settings,
        currentTitle = null,
        nextRunAt = null,
    )
}
