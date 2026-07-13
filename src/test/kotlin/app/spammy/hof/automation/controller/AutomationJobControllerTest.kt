package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.service.AutomationJobService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class AutomationJobControllerTest {
    private val automationJobService = Mockito.mock(AutomationJobService::class.java)
    private val controller = AutomationJobController(automationJobService)

    @Test
    fun createDelegatesToService() {
        val request = CreateAutomationJobRequest(profileId = 3L)
        Mockito.`when`(automationJobService.create(accountId = 1L, request = request))
            .thenReturn(job(status = "PENDING"))

        val response = controller.create(accountId = 1L, request = request)

        assertEquals("PENDING", response.status)
        Mockito.verify(automationJobService).create(accountId = 1L, request = request)
    }

    @Test
    fun statusActionsDelegateToService() {
        Mockito.`when`(automationJobService.pause(accountId = 1L, jobId = 5L)).thenReturn(job(status = "PAUSED"))
        Mockito.`when`(automationJobService.resume(accountId = 1L, jobId = 5L)).thenReturn(job(status = "RUNNING"))
        Mockito.`when`(automationJobService.cancel(accountId = 1L, jobId = 5L)).thenReturn(job(status = "CANCELLED"))

        assertEquals("PAUSED", controller.pause(accountId = 1L, jobId = 5L).status)
        assertEquals("RUNNING", controller.resume(accountId = 1L, jobId = 5L).status)
        assertEquals("CANCELLED", controller.cancel(accountId = 1L, jobId = 5L).status)
    }

    private fun job(status: String): AutomationJobResponse =
        AutomationJobResponse(
            id = 5L,
            accountId = 1L,
            profileId = 3L,
            status = status,
            currentStepIndex = 0,
            message = null,
            createdAt = "2026-07-08T00:00:00Z",
            startedAt = null,
            updatedAt = "2026-07-08T00:00:00Z",
            finishedAt = null,
        )
}
