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
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.raid.model.RaidAction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.ArgumentCaptor
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
        value = AutomationWorkStatus::class,
        names = ["WAITING_COOLDOWN", "WAITING_RESOURCE", "YIELDED_PRIORITY"],
    )
    fun `관측으로 끝난 낚시 대기를 닫아도 다른 작업권을 바꾸지 않는다`(status: AutomationWorkStatus) {
        val fishingEntry = AutomationEntryEntity(15, account, AutomationType.FISHING, 2, true, now, now)
        val fishing = AutomationWorkSessionEntity(
            id = 51, account = account, entry = fishingEntry, workType = AutomationWorkType.FISHING,
            targetKey = "DAILY_FISHING", status = status, configVersion = fishingEntry.updatedAt.toString(),
            nextCheckAt = now.plusSeconds(60), holdMessage = "결과 확인 대기", createdAt = now, updatedAt = now,
        )
        val other = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 12)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(other, fishing))

        service.completeFishingCycle(7, fishingEntry.id)

        assertEquals(AutomationWorkStatus.COMPLETED, fishing.status)
        assertEquals(null, fishing.nextCheckAt)
        assertEquals(null, fishing.holdMessage)
        assertEquals(now, fishing.finishedAt)
        assertEquals(AutomationWorkStatus.RUNNING, other.status)
        assertEquals(12, other.confirmedCount)
        Mockito.verify(commands).save(fishing)
        Mockito.verifyNoMoreInteractions(commands)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
        value = AutomationWorkStatus::class,
        names = ["WAITING_COOLDOWN", "WAITING_RESOURCE", "YIELDED_PRIORITY"],
    )
    fun `새 낚시 관측으로 대기 시각을 갱신해도 다른 작업권을 바꾸지 않는다`(status: AutomationWorkStatus) {
        val fishingEntry = AutomationEntryEntity(15, account, AutomationType.FISHING, 2, true, now, now)
        val fishing = AutomationWorkSessionEntity(
            id = 51, account = account, entry = fishingEntry, workType = AutomationWorkType.FISHING,
            targetKey = "DAILY_FISHING", status = status, configVersion = fishingEntry.updatedAt.toString(),
            nextCheckAt = now.plusSeconds(60), holdMessage = "결과 확인 대기", createdAt = now, updatedAt = now,
        )
        val other = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 12)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(other, fishing))

        service.waitFishingCycle(7, fishingEntry.id, now, "최신 CATCH 확인")

        assertEquals(AutomationWorkStatus.WAITING_COOLDOWN, fishing.status)
        assertEquals(now, fishing.nextCheckAt)
        assertEquals("최신 CATCH 확인", fishing.holdMessage)
        assertEquals(now, fishing.lastVerifiedAt)
        assertEquals(null, fishing.runningSlot)
        assertEquals(AutomationWorkStatus.RUNNING, other.status)
        assertEquals(12, other.confirmedCount)
        Mockito.verify(commands).save(fishing)
        Mockito.verifyNoMoreInteractions(commands)
    }

    @Test
    fun `preparing a different action transfers the sole running ownership`() {
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val runningQuest = AutomationWorkSessionEntity(
            id = 40,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = questEntry.updatedAt.toString(),
            confirmedCount = 2,
            questCycle = "cycle-3",
            missionKey = "mission-ajelad",
            observedCurrent = 4,
            observedRequired = 5,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, entry.id)).thenReturn(entry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(runningQuest))

        service.ensure(
            7,
            entry.id,
            AutomationWorkAssignment(
                AutomationWorkType.BATTLE_MAP,
                "battle_map/map-1",
                targetCount = 1,
            ),
        )

        assertEquals(AutomationWorkStatus.YIELDED_PRIORITY, runningQuest.status)
        assertEquals(2, runningQuest.confirmedCount)
        assertEquals("cycle-3", runningQuest.questCycle)
        assertEquals("mission-ajelad", runningQuest.missionKey)
        assertEquals(4, runningQuest.observedCurrent)
        assertEquals(5, runningQuest.observedRequired)
        val captor = ArgumentCaptor.forClass(AutomationWorkSessionEntity::class.java)
        Mockito.verify(commands).save(
            captor.capture() ?: AutomationWorkSessionEntity(
                account = account,
                entry = entry,
                workType = AutomationWorkType.BATTLE_MAP,
                targetKey = "capture-fallback",
                status = AutomationWorkStatus.RUNNING,
                configVersion = "capture-fallback",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val started = captor.value
        assertEquals(AutomationWorkStatus.RUNNING, started.status)
    }

    @Test
    fun `matching action repairs duplicate running sessions and keeps the latest selected owner`() {
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val older = AutomationWorkSessionEntity(
            id = 41,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = questEntry.updatedAt.toString(),
            confirmedCount = 1,
            questCycle = "older-cycle",
            createdAt = now,
            updatedAt = now,
        )
        val latest = AutomationWorkSessionEntity(
            id = 42,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = questEntry.updatedAt.toString(),
            confirmedCount = 4,
            questCycle = "selected-cycle",
            missionKey = "mission-selected",
            observedCurrent = 3,
            observedRequired = 5,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, questEntry.id)).thenReturn(questEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(older, latest))

        service.ensure(
            7,
            questEntry.id,
            AutomationWorkAssignment(AutomationWorkType.QUEST, "quest-1"),
        )

        assertEquals(AutomationWorkStatus.YIELDED_PRIORITY, older.status)
        assertEquals(AutomationWorkStatus.RUNNING, latest.status)
        assertEquals(4, latest.confirmedCount)
        assertEquals("selected-cycle", latest.questCycle)
        assertEquals("mission-selected", latest.missionKey)
        assertEquals(3, latest.observedCurrent)
        assertEquals(5, latest.observedRequired)
    }

    @Test
    fun `resuming a due session transfers ownership and preserves its progress`() {
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val current = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 12)
        val due = AutomationWorkSessionEntity(
            id = 43,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.YIELDED_PRIORITY,
            configVersion = questEntry.updatedAt.toString(),
            confirmedCount = 2,
            questCycle = "cycle-3",
            missionKey = "mission-ajelad",
            missionType = "MONSTER_KILL",
            observedCurrent = 4,
            observedRequired = 5,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, due.id)).thenReturn(due)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(current, due))
        Mockito.`when`(typed.findEntry(7, questEntry.id)).thenReturn(questEntry)

        service.resumeForCheck(7, due.id)

        assertEquals(AutomationWorkStatus.YIELDED_PRIORITY, current.status)
        assertEquals(AutomationWorkStatus.RUNNING, due.status)
        assertEquals(2, due.confirmedCount)
        assertEquals("cycle-3", due.questCycle)
        assertEquals("mission-ajelad", due.missionKey)
        assertEquals("MONSTER_KILL", due.missionType)
        assertEquals(4, due.observedCurrent)
        assertEquals(5, due.observedRequired)
    }

    @Test
    fun `priority handoff yields the old owner and resumes the due work under one locked transition`() {
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val current = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 12)
        val due = AutomationWorkSessionEntity(
            id = 44,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            configVersion = questEntry.updatedAt.toString(),
            nextCheckAt = now,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(current, due))

        assertEquals(true, service.handoffForPriority(7, current.id, due.id))

        assertEquals(AutomationWorkStatus.YIELDED_PRIORITY, current.status)
        assertEquals(now.plusSeconds(10), current.nextCheckAt)
        assertEquals(AutomationWorkStatus.RUNNING, due.status)
        assertEquals(null, due.nextCheckAt)
        Mockito.verify(queries, Mockito.never()).lockById(7, current.id)
        Mockito.verify(queries, Mockito.never()).lockById(7, due.id)
    }

    @Test
    fun `yielded battle session resumes with confirmed wins intact`() {
        val session = battleSession(status = AutomationWorkStatus.YIELDED_PRIORITY, confirmedCount = 12)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 21)).thenReturn(session)
        Mockito.`when`(typed.findEntry(7, entry.id)).thenReturn(entry)

        service.resumeForCheck(7, 21)

        assertEquals(12, session.confirmedCount)
        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        assertEquals(null, session.nextCheckAt)
    }

    @Test
    fun `quest session yields for a higher priority due target and keeps its assignment`() {
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 22,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            confirmedCount = 2,
            questCycle = "cycle-3",
            missionKey = "mission-ajelad",
            missionType = "MONSTER_KILL",
            observedCurrent = 4,
            observedRequired = 5,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 22)).thenReturn(session)
        Mockito.`when`(typed.findEntry(7, questEntry.id)).thenReturn(questEntry)

        assertEquals(true, service.yieldForPriority(7, 22))

        assertEquals(AutomationWorkStatus.YIELDED_PRIORITY, session.status)
        assertEquals(now.plusSeconds(10), session.nextCheckAt)
        service.resumeForCheck(7, 22)

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        assertEquals("quest-1", session.targetKey)
        assertEquals(2, session.confirmedCount)
        assertEquals("cycle-3", session.questCycle)
        assertEquals("mission-ajelad", session.missionKey)
        assertEquals("MONSTER_KILL", session.missionType)
        assertEquals(4, session.observedCurrent)
        assertEquals(5, session.observedRequired)
        assertEquals(null, session.nextCheckAt)
        Mockito.verify(commands, Mockito.times(2)).save(session)
    }

    @Test
    fun `generic work lifecycle projects a module resource wait transition`() {
        val session = AutomationWorkSessionEntity(
            id = 24,
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
        Mockito.`when`(queries.lockById(7, 24)).thenReturn(session)

        service.applyTransition(
            7,
            24,
            AutomationWorkTransition.WaitForResource("steel ingot", 2),
        )

        assertEquals(AutomationWorkStatus.WAITING_RESOURCE, session.status)
        assertEquals("steel ingot", session.materialName)
        assertEquals(2, session.materialMissing)
        Mockito.verify(commands).save(session)
    }

    @Test
    fun `configuration wait releases the running slot before another work is prepared`() {
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val questSession = AutomationWorkSessionEntity(
            id = 25,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "quest-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 25)).thenReturn(questSession)

        service.applyTransition(
            7,
            25,
            AutomationWorkTransition.WaitForConfiguration("bad preset"),
        )

        assertEquals(AutomationWorkStatus.WAITING_COOLDOWN, questSession.status)
        assertEquals("bad preset", questSession.holdMessage)
        Mockito.`when`(typed.findEntry(7, entry.id)).thenReturn(entry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(questSession))

        service.ensure(
            7,
            entry.id,
            AutomationWorkAssignment(
                AutomationWorkType.BATTLE_MAP,
                "battle_map/map-1",
                targetCount = 1,
            ),
        )

        val saves = Mockito.mockingDetails(commands).invocations.filter { it.method.name == "save" }
        assertEquals(2, saves.size)
        val started = saves.last().arguments.single() as AutomationWorkSessionEntity
        assertEquals(AutomationWorkStatus.RUNNING, started.status)
        assertEquals(AutomationWorkType.BATTLE_MAP, started.workType)
    }

    @Test
    fun `matching adventure action completes its one battle work unit`() {
        val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 23,
            account = account,
            entry = adventureEntry,
            workType = AutomationWorkType.ADVENTURE_MAP,
            targetKey = "sp_hunt/map-1",
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.completeAdventureAction(7, 12, "sp_hunt", "map-1")

        assertEquals(AutomationWorkStatus.COMPLETED, session.status)
        assertEquals(now, session.finishedAt)
        Mockito.verify(commands).save(session)
    }

    @Test
    fun `matching battle map action completes its one request work unit`() {
        val matching = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 0)
        val other = AutomationWorkSessionEntity(
            id = 26,
            account = account,
            entry = entry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-2",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            configVersion = "config-v1",
            targetCount = 20,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(matching, other))

        service.completeBattleMapAction(7, 11, "battle_map", "map-1")

        assertEquals(AutomationWorkStatus.COMPLETED, matching.status)
        assertEquals(now, matching.finishedAt)
        assertEquals(AutomationWorkStatus.WAITING_COOLDOWN, other.status)
        Mockito.verify(commands).save(matching)
        Mockito.verify(commands, Mockito.never()).save(other)
    }

    @Test
    fun `configuration change stops only matching target sessions`() {
        val matching = battleSession(status = AutomationWorkStatus.RUNNING, confirmedCount = 5)
        val other = AutomationWorkSessionEntity(
            id = 25,
            account = account,
            entry = entry,
            workType = AutomationWorkType.BATTLE_MAP,
            targetKey = "battle_map/map-2",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            configVersion = "config-v1",
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(matching, other))

        service.stopForConfigurationChange(7, entry.id, setOf("battle_map/map-1"), false)

        assertEquals(AutomationWorkStatus.STOPPED, matching.status)
        assertEquals(AutomationWorkStatus.WAITING_COOLDOWN, other.status)
        Mockito.verify(commands).save(matching)
        Mockito.verify(commands, Mockito.never()).save(other)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AutomationWorkType::class)
    fun `모든 유형은 수정 시각이 다른 보존 작업을 준비 경로에서도 이어간다`(type: AutomationWorkType) {
        val preservedEntry = AutomationEntryEntity(11, account, AutomationType.valueOf(type.name), 0, true, now, now)
        val session = AutomationWorkSessionEntity(id = 21, account = account, entry = preservedEntry,
            workType = type, targetKey = "kept-target", status = AutomationWorkStatus.WAITING_RESOURCE,
            configVersion = now.minusSeconds(86400).toString(), confirmedCount = 4,
            questCycle = "kept-cycle", missionKey = "kept-mission", createdAt = now, updatedAt = now)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, preservedEntry.id)).thenReturn(preservedEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.ensure(7, preservedEntry.id, AutomationWorkAssignment(type, session.targetKey, targetCount = 10))

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        assertEquals(4, session.confirmedCount)
        assertEquals("kept-cycle", session.questCycle)
        assertEquals("kept-mission", session.missionKey)
        assertEquals(null, session.finishedAt)
    }

    @Test
    fun `entry version change alone does not stop a parked target`() {
        val session = battleSession(status = AutomationWorkStatus.YIELDED_PRIORITY, confirmedCount = 4)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockById(7, 21)).thenReturn(session)
        Mockito.`when`(typed.findEntry(7, entry.id)).thenReturn(entry)

        service.resumeForCheck(7, 21)

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        assertEquals(null, session.finishedAt)
    }

    @Test
    fun `battle work assignment starts one session with configured victory target`() {
        val setting = BattleAutomationMapEntity(
            id = 31,
            entry = entry,
            categoryId = "battle_map",
            mapCode = "map-1",
            dailyTargetCount = 20,
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, 11)).thenReturn(entry)
        Mockito.`when`(typed.findBattleSettings(11)).thenReturn(listOf(setting))
        Mockito.`when`(queries.lockOpen(7)).thenReturn(emptyList())

        service.ensure(
            7,
            11,
            AutomationWorkAssignment(AutomationWorkType.BATTLE_MAP, "battle_map/map-1"),
        )
        val started = Mockito.mockingDetails(commands).invocations.single { it.method.name == "save" }
            .arguments.single() as AutomationWorkSessionEntity

        assertEquals(AutomationWorkType.BATTLE_MAP, started.workType)
        assertEquals("battle_map/map-1", started.targetKey)
        assertEquals(20, started.targetCount)
        assertEquals(AutomationWorkStatus.RUNNING, started.status)
    }

    @Test
    fun `generic work ownership does not interpret quest cycle progress`() {
        val questEntry = AutomationEntryEntity(12, account, AutomationType.QUEST, 2, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 24,
            account = account,
            entry = questEntry,
            workType = AutomationWorkType.QUEST,
            targetKey = "repeat-q",
            status = AutomationWorkStatus.RUNNING,
            configVersion = questEntry.updatedAt.toString(),
            questCycle = null,
            confirmedCount = 4,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, questEntry.id)).thenReturn(questEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.ensure(
            7,
            questEntry.id,
            AutomationWorkAssignment(AutomationWorkType.QUEST, "repeat-q"),
        )

        assertEquals(null, session.questCycle)
        assertEquals(4, session.confirmedCount)
        Mockito.verifyNoInteractions(commands)
    }

    @Test
    fun `fishing work assignment keeps the daily session across catch`() {
        val fishingEntry = AutomationEntryEntity(13, account, AutomationType.FISHING, 3, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 27,
            account = account,
            entry = fishingEntry,
            workType = AutomationWorkType.FISHING,
            targetKey = FishingAction.START.name,
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, fishingEntry.id)).thenReturn(fishingEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.ensure(
            7,
            fishingEntry.id,
            AutomationWorkAssignment(AutomationWorkType.FISHING, FISHING_CYCLE_TARGET),
        )

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        Mockito.verifyNoInteractions(commands)
    }

    @Test
    fun `fishing battle continues the same daily fishing session`() {
        val fishingEntry = AutomationEntryEntity(13, account, AutomationType.FISHING, 3, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 28,
            account = account,
            entry = fishingEntry,
            workType = AutomationWorkType.FISHING,
            targetKey = FishingAction.START.name,
            status = AutomationWorkStatus.RUNNING,
            configVersion = "config-v1",
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, fishingEntry.id)).thenReturn(fishingEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.ensure(
            7,
            fishingEntry.id,
            AutomationWorkAssignment(AutomationWorkType.FISHING, FISHING_CYCLE_TARGET),
        )

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        Mockito.verifyNoInteractions(commands)
    }

    @Test
    fun `raid battle continues the work session opened by registration`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 29,
            account = account,
            entry = raidEntry,
            workType = AutomationWorkType.RAID,
            targetKey = "RaidGoblin",
            status = AutomationWorkStatus.RUNNING,
            configVersion = raidEntry.updatedAt.toString(),
            createdAt = now,
            updatedAt = now,
        )
        val action = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "raid",
            mapCode = "raid001",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "raid-battle-1",
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, raidEntry.id)).thenReturn(raidEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.ensure(
            7,
            raidEntry.id,
            AutomationWorkAssignment(AutomationWorkType.RAID, requireNotNull(action.sourceTargetKey)),
        )

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        Mockito.verifyNoInteractions(commands)
    }

    @Test
    fun `raid cooldown refresh opens a session for the configured raid target`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val action = RaidTownAutomationAction(
            accountId = 7,
            action = RaidAction.REFRESH,
            targetRaidId = "RaidGoblin",
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, raidEntry.id)).thenReturn(raidEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(emptyList())

        service.ensure(
            7,
            raidEntry.id,
            AutomationWorkAssignment(AutomationWorkType.RAID, requireNotNull(action.targetRaidId)),
        )

        val captor = ArgumentCaptor.forClass(AutomationWorkSessionEntity::class.java)
        Mockito.verify(commands).save(
            captor.capture() ?: AutomationWorkSessionEntity(
                account = account,
                entry = raidEntry,
                workType = AutomationWorkType.RAID,
                targetKey = "capture-fallback",
                status = AutomationWorkStatus.RUNNING,
                configVersion = "capture-fallback",
                createdAt = now,
                updatedAt = now,
            ),
        )
        assertEquals(AutomationWorkType.RAID, captor.value.workType)
        assertEquals("RaidGoblin", captor.value.targetKey)
        assertEquals(AutomationWorkStatus.RUNNING, captor.value.status)
    }

    @Test
    fun `raid wait opens a parked session before its first action`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val retryAt = now.plusSeconds(120)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, raidEntry.id)).thenReturn(raidEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(emptyList())

        service.waitForRaid(7, raidEntry.id, "RaidGoblin", retryAt, "수동 레이드 진행 중")

        val captor = ArgumentCaptor.forClass(AutomationWorkSessionEntity::class.java)
        Mockito.verify(commands).save(
            captor.capture() ?: AutomationWorkSessionEntity(
                account = account,
                entry = raidEntry,
                workType = AutomationWorkType.RAID,
                targetKey = "capture-fallback",
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = "capture-fallback",
                createdAt = now,
                updatedAt = now,
            ),
        )
        assertEquals(AutomationWorkType.RAID, captor.value.workType)
        assertEquals("RaidGoblin", captor.value.targetKey)
        assertEquals(AutomationWorkStatus.WAITING_COOLDOWN, captor.value.status)
        assertEquals(retryAt, captor.value.nextCheckAt)
        assertEquals("수동 레이드 진행 중", captor.value.holdMessage)
    }

    @Test
    fun `configured raid action retargets a parked manual raid hold session`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 31,
            account = account,
            entry = raidEntry,
            workType = AutomationWorkType.RAID,
            targetKey = "ManualRaid",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            configVersion = raidEntry.updatedAt.toString(),
            nextCheckAt = now,
            createdAt = now,
            updatedAt = now,
        )
        val action = RaidTownAutomationAction(
            accountId = 7,
            action = RaidAction.REGISTER,
            raidId = "RaidGoblin",
            targetRaidId = "RaidGoblin",
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, raidEntry.id)).thenReturn(raidEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.ensure(
            7,
            raidEntry.id,
            AutomationWorkAssignment(AutomationWorkType.RAID, requireNotNull(action.targetRaidId)),
        )

        assertEquals(AutomationWorkStatus.RUNNING, session.status)
        assertEquals("RaidGoblin", session.targetKey)
        assertEquals(null, session.nextCheckAt)
        Mockito.verify(commands).save(session)
    }

    @Test
    fun `preset change makes every parked raid session due immediately`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val waitingRaid = AutomationWorkSessionEntity(
            id = 32,
            account = account,
            entry = raidEntry,
            workType = AutomationWorkType.RAID,
            targetKey = "RaidGoblin",
            status = AutomationWorkStatus.WAITING_COOLDOWN,
            configVersion = raidEntry.updatedAt.toString(),
            nextCheckAt = null,
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60),
        )
        val waitingBattle = battleSession(AutomationWorkStatus.WAITING_COOLDOWN, confirmedCount = 0).also {
            it.nextCheckAt = now.plusSeconds(300)
        }
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(waitingRaid, waitingBattle))

        service.triggerRaidConfigurationCheck(7)

        assertEquals(now, waitingRaid.nextCheckAt)
        assertEquals(now, waitingRaid.updatedAt)
        assertEquals(now.plusSeconds(300), waitingBattle.nextCheckAt)
        Mockito.verify(commands).save(waitingRaid)
        Mockito.verify(commands, Mockito.never()).save(waitingBattle)
    }

    @Test
    fun `raid cycle completion closes the whole raid work session`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val session = AutomationWorkSessionEntity(
            id = 30,
            account = account,
            entry = raidEntry,
            workType = AutomationWorkType.RAID,
            targetKey = "RaidGoblin",
            status = AutomationWorkStatus.RUNNING,
            configVersion = raidEntry.updatedAt.toString(),
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        service.completeRaidCycle(7, raidEntry.id)

        assertEquals(AutomationWorkStatus.COMPLETED, session.status)
        assertEquals(now, session.finishedAt)
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
