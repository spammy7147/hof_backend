package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class AutomationExecutionSignalServiceTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private val account = HofAccountEntity(7, "execution-signals", "encrypted", now)
    private val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
    private val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
    private val queries = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val lifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val service = AutomationExecutionSignalService(
        queries,
        lifecycle,
        AutomationLootSignalService(),
        TimeProvider { now },
    )

    @Test
    fun `matching material loot yields running battle map after its response`() {
        val running = session(21, battleEntry, AutomationWorkType.BATTLE_MAP, "battle_map/map", AutomationWorkStatus.RUNNING)
        val waiting = session(22, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.WAITING_RESOURCE).apply {
            materialName = "steel ingot"
        }
        Mockito.`when`(queries.findRunning(7)).thenReturn(running)
        Mockito.`when`(queries.findWaiting(7)).thenReturn(listOf(waiting))
        Mockito.`when`(lifecycle.yieldForPriority(7, 21)).thenReturn(true)

        val yielded = service.afterBattle(
            accountId = 7,
            source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
            lootNames = listOf("ＳＴＥＥＬ Ingot x 2"),
            questTexts = emptyList(),
        )

        assertTrue(yielded)
        Mockito.verify(lifecycle).triggerCheck(7, 22)
        Mockito.verify(lifecycle).yieldForPriority(7, 21)
    }

    @Test
    fun `unrelated loot does not yield battle session`() {
        val running = session(21, battleEntry, AutomationWorkType.BATTLE_MAP, "battle_map/map", AutomationWorkStatus.RUNNING)
        val waiting = session(22, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.WAITING_RESOURCE).apply {
            materialName = "steel ingot"
        }
        Mockito.`when`(queries.findRunning(7)).thenReturn(running)
        Mockito.`when`(queries.findWaiting(7)).thenReturn(listOf(waiting))

        assertFalse(
            service.afterBattle(
                accountId = 7,
                source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
                lootNames = listOf("Stone"),
                questTexts = emptyList(),
            ),
        )
        Mockito.verify(lifecycle, Mockito.never()).yieldForPriority(7, 21)
    }

    @Test
    fun `quest map clear records only terminal victories without yielding`() {
        val running = session(23, questEntry, AutomationWorkType.QUEST, "quest-1", AutomationWorkStatus.RUNNING).apply {
            missionType = app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR.name
            observedCurrent = 3
            observedRequired = 5
        }
        Mockito.`when`(queries.findRunning(7)).thenReturn(running)

        assertFalse(
            service.afterBattle(
                accountId = 7,
                source = BattleAutomationActionSource.QUEST_AUTOMATION,
                outcomes = listOf(BattleAutomationRoundOutcome.VICTORY, BattleAutomationRoundOutcome.DEFEAT),
                lootNames = emptyList(),
                questTexts = emptyList(),
            ),
        )

        Mockito.verify(lifecycle).recordQuestVictories(7, 23, 1)
        Mockito.verify(lifecycle, Mockito.never()).yieldForPriority(7, 23)
    }

    private fun session(
        id: Long,
        entry: AutomationEntryEntity,
        type: AutomationWorkType,
        targetKey: String,
        status: AutomationWorkStatus,
    ) = AutomationWorkSessionEntity(
        id = id,
        account = account,
        entry = entry,
        workType = type,
        targetKey = targetKey,
        status = status,
        configVersion = "config-v1",
        createdAt = now,
        updatedAt = now,
    )
}
