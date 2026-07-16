package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.service.AutomationJobService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class AutomationJobControllerTest {
    private val automationJobService = Mockito.mock(AutomationJobService::class.java)
    private val controller = AutomationJobController(automationJobService)

    @Test
    fun currentLegacyJobReadRemainsAvailable() {
        Mockito.`when`(automationJobService.findCurrent(1L)).thenReturn(job())

        assertEquals(5L, controller.findCurrent(1L)?.id)
        Mockito.verify(automationJobService).findCurrent(1L)
    }

    @Test
    fun retiredMutationsExposeTheExplicitGoneErrorCode() {
        val retired = ApiException(ErrorCode.LEGACY_AUTOMATION_RETIRED, "retired")
        val request = CreateAutomationJobRequest(profileId = 3L)
        Mockito.`when`(automationJobService.create(1L, request)).thenThrow(retired)
        Mockito.`when`(automationJobService.pause(1L, 5L)).thenThrow(retired)
        Mockito.`when`(automationJobService.resume(1L, 5L)).thenThrow(retired)
        Mockito.`when`(automationJobService.cancel(1L, 5L)).thenThrow(retired)

        listOf<() -> Unit>(
            { controller.create(1L, request) },
            { controller.pause(1L, 5L) },
            { controller.resume(1L, 5L) },
            { controller.cancel(1L, 5L) },
        ).forEach { call ->
            val error = assertFailsWith<ApiException> { call() }
            assertEquals(ErrorCode.LEGACY_AUTOMATION_RETIRED, error.errorCode)
            assertEquals(HttpStatus.GONE, error.errorCode.status)
        }
    }

    private fun job(): AutomationJobResponse =
        AutomationJobResponse(
            id = 5L,
            accountId = 1L,
            profileId = 3L,
            status = "CANCELLED",
            currentStepIndex = 0,
            message = "history",
            createdAt = "2026-07-08T00:00:00Z",
            startedAt = null,
            updatedAt = "2026-07-08T00:00:00Z",
            finishedAt = "2026-07-08T00:00:00Z",
        )
}
