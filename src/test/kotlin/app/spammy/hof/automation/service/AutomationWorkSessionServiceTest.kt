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

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.yieldForPriority(7, 22)
        }
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
    fun `legacy fishing start session continues with catch action`() {
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
        val action = FishingTownAutomationAction(
            accountId = 7,
            action = FishingAction.CATCH,
            observedPrimaryAction = FishingPrimaryAction.CATCH,
            observedRemainingCasts = 9,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, fishingEntry.id)).thenReturn(fishingEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        val resumed = service.ensureForAction(7, fishingEntry.id, action)

        assertEquals(session, resumed)
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
        val action = BattleMapAutomationAction(
            accountId = 7,
            progressDate = java.time.LocalDate.parse("2026-07-23"),
            categoryId = "fishing",
            mapCode = "monster-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 3,
            battleCount = 1,
            executionIdentity = "fishing-battle-1",
            source = BattleAutomationActionSource.FISHING_AUTOMATION,
        )
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, fishingEntry.id)).thenReturn(fishingEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(listOf(session))

        val resumed = service.ensureForAction(7, fishingEntry.id, action)

        assertEquals(session, resumed)
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

        val resumed = service.ensureForAction(7, raidEntry.id, action)

        assertEquals(session, resumed)
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

        val started = service.ensureForAction(7, raidEntry.id, action)

        assertEquals(AutomationWorkType.RAID, started.workType)
        assertEquals("RaidGoblin", started.targetKey)
        assertEquals(AutomationWorkStatus.RUNNING, started.status)
        Mockito.verify(commands).save(started)
    }

    @Test
    fun `raid wait opens a parked session before its first action`() {
        val raidEntry = AutomationEntryEntity(14, account, AutomationType.RAID, 4, true, now, now)
        val retryAt = now.plusSeconds(120)
        Mockito.`when`(typed.lockRuntimeState(7)).thenReturn(runtime)
        Mockito.`when`(typed.findEntry(7, raidEntry.id)).thenReturn(raidEntry)
        Mockito.`when`(queries.lockOpen(7)).thenReturn(emptyList())

        service.waitForRaid(7, raidEntry.id, "RaidGoblin", retryAt)

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

        val resumed = service.ensureForAction(7, raidEntry.id, action)

        assertEquals(AutomationWorkStatus.RUNNING, resumed.status)
        assertEquals("RaidGoblin", resumed.targetKey)
        assertEquals(null, resumed.nextCheckAt)
        Mockito.verify(commands).save(resumed)
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
