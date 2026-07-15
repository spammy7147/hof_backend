package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.AutomationModuleResponse
import app.spammy.hof.automation.dto.CreateAutomationModuleRequest
import app.spammy.hof.automation.dto.ReorderAutomationModulesRequest
import app.spammy.hof.automation.dto.UnifiedAutomationStatusResponse
import app.spammy.hof.automation.dto.UpdateAutomationModuleRequest
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.service.UnifiedAutomationService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class UnifiedAutomationControllerTest {
    private val service = Mockito.mock(UnifiedAutomationService::class.java)
    private val controller = UnifiedAutomationController(service)

    @Test
    fun delegatesModuleCrudOrderAndLifecycleForTheAuthenticatedAccount() {
        val created = module(id = 31L, displayName = "Time 소모")
        val updated = created.copy(displayName = "보스용 Time")
        val reordered = status("RUNNING", listOf(created, module(id = 18L, displayName = "일일 던전")))
        Mockito.`when`(service.get(7L)).thenReturn(status("PAUSED"))
        Mockito.`when`(service.createModule(7L, createRequest())).thenReturn(created)
        Mockito.`when`(service.updateModule(7L, 31L, updateRequest())).thenReturn(updated)
        Mockito.`when`(service.reorderModules(7L, ReorderAutomationModulesRequest(listOf(31L, 18L))))
            .thenReturn(reordered)
        Mockito.`when`(service.start(7L)).thenReturn(status("RUNNING"))
        Mockito.`when`(service.pause(7L)).thenReturn(status("PAUSED"))
        Mockito.`when`(service.resume(7L)).thenReturn(status("RUNNING"))
        Mockito.`when`(service.stop(7L)).thenReturn(status("CANCELLED"))

        assertEquals(0, controller.get(7L).modules.size)
        assertEquals(31L, controller.createModule(7L, createRequest()).id)
        assertEquals("보스용 Time", controller.updateModule(7L, 31L, updateRequest()).displayName)
        assertEquals(
            listOf(31L, 18L),
            controller.reorder(7L, ReorderAutomationModulesRequest(listOf(31L, 18L))).modules.map { it.id },
        )
        controller.deleteModule(7L, 31L)
        assertEquals("RUNNING", controller.start(7L).job?.status)
        assertEquals("PAUSED", controller.pause(7L).job?.status)
        assertEquals("RUNNING", controller.resume(7L).job?.status)
        assertEquals("CANCELLED", controller.stop(7L).job?.status)

        Mockito.verify(service).deleteModule(7L, 31L)
    }

    private fun createRequest() = CreateAutomationModuleRequest(
        displayName = "Time 소모",
        moduleType = AutomationModuleType.TIME_BURN,
        enabled = true,
        thresholdPercent = 90,
    )

    private fun updateRequest() = UpdateAutomationModuleRequest(
        displayName = "보스용 Time",
        enabled = true,
        thresholdPercent = 85,
    )

    private fun module(
        id: Long,
        displayName: String,
    ) = AutomationModuleResponse(
        id = id,
        displayName = displayName,
        moduleType = AutomationModuleType.TIME_BURN,
        enabled = true,
        priority = 0,
        thresholdPercent = 90,
        maps = emptyList(),
        quests = emptyList(),
        ready = false,
        summary = "전투 맵과 파티를 설정해 주세요.",
    )

    private fun status(
        jobStatus: String?,
        modules: List<AutomationModuleResponse> = emptyList(),
    ) = UnifiedAutomationStatusResponse(
        profileId = 3L,
        job = jobStatus?.let {
            AutomationJobResponse(
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
        modules = modules,
        currentTitle = null,
        nextRunAt = null,
    )
}
