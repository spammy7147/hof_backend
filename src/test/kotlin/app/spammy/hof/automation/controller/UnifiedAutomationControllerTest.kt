package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.CreateAutomationEntryRequest
import app.spammy.hof.automation.dto.ReorderAutomationEntriesRequest
import app.spammy.hof.automation.dto.TypedAutomationAggregateResponse
import app.spammy.hof.automation.dto.TypedAutomationRuntimeResponse
import app.spammy.hof.automation.dto.UpdateAdventureMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateBattleMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateQuestAutomationRequest
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.service.UnifiedAutomationService
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class UnifiedAutomationControllerTest {
    private val service = Mockito.mock(UnifiedAutomationService::class.java)
    private val controller = UnifiedAutomationController(service)

    @Test
    fun delegatesTypedSettingsAndLifecycleForTheAuthenticatedAccount() {
        val aggregate = TypedAutomationAggregateResponse(
            entries = emptyList(),
            runtime = TypedAutomationRuntimeResponse(TypedAutomationLifecycle.STOPPED),
        )
        val create = CreateAutomationEntryRequest(AutomationType.QUEST)
        val reorder = ReorderAutomationEntriesRequest(listOf(31L))
        val quest = UpdateQuestAutomationRequest(false, emptyList())
        val battle = UpdateBattleMapAutomationRequest(false, emptyList())
        val adventure = UpdateAdventureMapAutomationRequest(false, emptyList())
        Mockito.`when`(service.getTyped(7L)).thenReturn(aggregate)
        Mockito.`when`(service.createEntry(7L, create)).thenReturn(aggregate)
        Mockito.`when`(service.deleteEntry(7L, 31L)).thenReturn(aggregate)
        Mockito.`when`(service.reorderEntries(7L, reorder)).thenReturn(aggregate)
        Mockito.`when`(service.updateQuest(7L, quest)).thenReturn(aggregate)
        Mockito.`when`(service.updateBattleMaps(7L, battle)).thenReturn(aggregate)
        Mockito.`when`(service.updateAdventureMaps(7L, adventure)).thenReturn(aggregate)
        Mockito.`when`(service.startTyped(7L)).thenReturn(aggregate)
        Mockito.`when`(service.pauseTyped(7L)).thenReturn(aggregate)
        Mockito.`when`(service.resumeTyped(7L)).thenReturn(aggregate)
        Mockito.`when`(service.stopTyped(7L)).thenReturn(aggregate)

        assertEquals(aggregate, controller.get(7L))
        assertEquals(aggregate, controller.createEntry(7L, create))
        assertEquals(aggregate, controller.deleteEntry(7L, 31L))
        assertEquals(aggregate, controller.reorder(7L, reorder))
        assertEquals(aggregate, controller.updateQuest(7L, quest))
        assertEquals(aggregate, controller.updateBattleMaps(7L, battle))
        assertEquals(aggregate, controller.updateAdventureMaps(7L, adventure))
        assertEquals(aggregate, controller.start(7L))
        assertEquals(aggregate, controller.pause(7L))
        assertEquals(aggregate, controller.resume(7L))
        assertEquals(aggregate, controller.stop(7L))
    }
}
