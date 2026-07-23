package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class AutomationWorkSessionServiceTest {
    private val now = Instant.parse("2026-07-23T00:00:00Z")
    private val account = HofAccountEntity(7, "session-service", "encrypted", now)
    private val entry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
    private val runtime = TypedAutomationRuntimeStateEntity(7, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now)
    private val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val queries = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val commands = Mockito.mock(AutomationWorkSessionCommandRepository::class.java)
    private val service = AutomationWorkSessionService(typed, queries, commands, TimeProvider { now })

    @Test
    fun `yielded battle session resumes with confirmed wins intact`() {
        val session = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 12)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 21)).thenReturn(session)

        assertEquals(true, service.yieldForPriority(7, 21))
        val resumed = service.resume(7, 21, "config-v1")

        assertEquals(12, resumed.confirmedCount)
        assertEquals(AutomationWorkStatus.RUNNING, resumed.status)
        assertEquals(null, resumed.nextCheckAt)
    }

    @Test
    fun `quest and adventure sessions cannot yield for priority`() {
        val session = AutomationWorkSessionEntity(
            id = 22,
            account = account,
            entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now),
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 22)).thenReturn(session)

        assertFailsWith<IllegalArgumentException> {
            service.yieldForPriority(7, 22)
        }
    }

    @Test
    fun `configuration mismatch stops stale session instead of resuming it`() {
        val session = battleSession(status = AutomationWorkStatus.YIELDED_PRIORITY, confirmedCount = 4)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 21)).thenReturn(session)

        assertFailsWith<AutomationWorkConfigurationChangedException> {
            service.resume(7, 21, "config-v2")
        }

        assertEquals(AutomationWorkStatus.STOPPED, session.status)
        assertEquals(now, session.finishedAt)
    }

    @Test
    fun `battle action starts one session with configured victory target`() {
        val setting = BattleAutomationMapEntity(
            id = 31,
            entry = entry,
            categoryId = "battle_map",
            mapCode = "map-1",
            dailyTargetCount = 20,
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        val action = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 3,
            executionIdentity = "execution-1",
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, 11)).thenReturn(entry)
        Mockito.`when`(typed.findBattleSettings(11)).thenReturn(listOf(setting))
        Mockito.`when`(queries.lockOpen(7)).thenReturn(emptyList())

        val started = service.ensureForAction(7, 11, action)

        assertEquals(AutomationWorkType.BATTLE_MAP, started.workType)
        assertEquals("battle_map/map-1", started.targetKey)
        assertEquals(20, started.targetCount)
        assertEquals(AutomationWorkStatus.RUNNING, started.status)
        Mockito.verify(commands).save(started)
    }

    @Test
    fun `map clear victories advance optimistic quest progress`() {
        val session = AutomationWorkSessionEntity(
            id = 24,
            account = account,
            entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now),
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            missionType = app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR.name,
            observedCurrent = 3,
            observedRequired = 5,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 24)).thenReturn(session)

        service.recordQuestVictories(7, 24, 1)

        assertEquals(4, session.observedCurrent)
        assertEquals(1, session.confirmedCount)
        Mockito.verify(commands).save(session)
    }

    @Test
    fun `authoritative quest progress corrects an optimistic mismatch`() {
        val session = AutomationWorkSessionEntity(
            id = 25,
            account = account,
            entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now),
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            missionType = app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR.name,
            observedCurrent = 5,
            observedRequired = 5,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 25)).thenReturn(session)

        service.reconcileQuestProgress(7, 25, 4, 5)

        assertEquals(4, session.observedCurrent)
        assertEquals(5, session.observedRequired)
        assertEquals(now, session.lastVerifiedAt)
        Mockito.verify(commands).save(session)
    }

    private fun battleSession(status: AutomationWorkStatus, confirmedCount: Int) =
        AutomationWorkSessionEntity(
            id = 21,
            account = account,
            entry = entry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-1",
            status = status,
            configVersion = "config-v1",
            targetCount = 20,
            confirmedCount = confirmedCount,
            createdAt = now,
            updatedAt = now,
        )
}
