package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.BattleMapSettingRequest
import app.spammy.hof.automation.dto.CreateAutomationEntryRequest
import app.spammy.hof.automation.dto.QuestMapSettingRequest
import app.spammy.hof.automation.dto.QuestSelectionRequest
import app.spammy.hof.automation.dto.ReorderAutomationEntriesRequest
import app.spammy.hof.automation.dto.UpdateBattleMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateBattleMapGroupRequest
import app.spammy.hof.automation.dto.UpdateFishingAutomationRequest
import app.spammy.hof.automation.dto.FishingMapSettingRequest
import app.spammy.hof.automation.dto.HomeQuestSelectionRequest
import app.spammy.hof.automation.dto.MoveMapBetweenGroupsRequest
import app.spammy.hof.automation.dto.UnionMapSettingRequest
import app.spammy.hof.automation.dto.UpdateUnionAutomationRequest
import app.spammy.hof.automation.dto.RaidTargetSettingRequest
import app.spammy.hof.automation.dto.UpdateRaidAutomationRequest
import app.spammy.hof.automation.dto.UpdateAdventureMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateHomeQuestAutomationRequest
import app.spammy.hof.automation.dto.UpdateQuestAutomationRequest
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.AdventureAutomationMapEntity
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.FishingAutomationSettingEntity
import app.spammy.hof.automation.entity.FishingAutomationMapEntity
import app.spammy.hof.automation.entity.HomeQuestAutomationSelectionEntity
import app.spammy.hof.automation.entity.UnionAutomationMapEntity
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidBattleRecoveryObservation
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.entity.RaidAutomationTargetEntity
import app.spammy.hof.automation.entity.RaidAutomationCycleEntity
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationMapEntity
import app.spammy.hof.automation.entity.QuestAutomationSelectionEntity
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.AdventureAutomationMapCommandRepository
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionView
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.automation.repository.FishingAutomationSettingCommandRepository
import app.spammy.hof.automation.repository.FishingAutomationMapCommandRepository
import app.spammy.hof.automation.repository.HomeQuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.UnionAutomationMapCommandRepository
import app.spammy.hof.automation.repository.RaidAutomationTargetCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationMapCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.quest.model.QuestIdentityFactory
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.status.dto.HofObservedStatusResponse
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class UnifiedAutomationServiceTest {
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val battleMapQueryRepository = Mockito.mock(BattleMapQueryRepository::class.java)
    private val partyPresetQueryRepository = Mockito.mock(PartyPresetQueryRepository::class.java)
    private val typedQuery = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val lifecycle = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val workSessionQueries = Mockito.mock(AutomationWorkSessionQueryRepository::class.java)
    private val entryRepository = Mockito.mock(AutomationEntryCommandRepository::class.java)
    private val questSelectionRepository = Mockito.mock(QuestAutomationSelectionCommandRepository::class.java)
    private val questMapRepository = Mockito.mock(QuestAutomationMapCommandRepository::class.java)
    private val homeQuestSelectionRepository = Mockito.mock(HomeQuestAutomationSelectionCommandRepository::class.java)
    private val battleSettingRepository = Mockito.mock(BattleAutomationMapCommandRepository::class.java)
    private val adventureSettingRepository = Mockito.mock(AdventureAutomationMapCommandRepository::class.java)
    private val fishingSettingRepository = Mockito.mock(FishingAutomationSettingCommandRepository::class.java)
    private val fishingMapRepository = Mockito.mock(FishingAutomationMapCommandRepository::class.java)
    private val unionSettingRepository = Mockito.mock(UnionAutomationMapCommandRepository::class.java)
    private val raidTargetRepository = Mockito.mock(RaidAutomationTargetCommandRepository::class.java)
    private val automationOutbox = Mockito.mock(AutomationOutboxService::class.java)
    private val statusSnapshots = Mockito.mock(HofStatusSnapshotService::class.java)
    private val raidCycleModule = Mockito.mock(RaidCycleModule::class.java)
    private val decisionJournal = Mockito.mock(AutomationDecisionJournal::class.java)
    private val actionLifecycleModule = Mockito.mock(AutomationActionLifecycleModule::class.java)
    private var currentTime = NOW
    private val service = UnifiedAutomationService(
        accountQueryRepository = accountQueryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        partyPresetQueryRepository = partyPresetQueryRepository,
        timeProvider = TimeProvider { currentTime },
        typedAutomationQueryRepository = typedQuery,
        typedLifecycleBridge = lifecycle,
        typedEntryRepository = entryRepository,
        typedQuestSelectionRepository = questSelectionRepository,
        typedQuestMapRepository = questMapRepository,
        typedHomeQuestSelectionRepository = homeQuestSelectionRepository,
        typedBattleMapRepository = battleSettingRepository,
        typedAdventureMapRepository = adventureSettingRepository,
        typedFishingSettingRepository = fishingSettingRepository,
        typedFishingMapRepository = fishingMapRepository,
        typedUnionMapRepository = unionSettingRepository,
        typedRaidTargetRepository = raidTargetRepository,
        automationOutboxService = automationOutbox,
        hofStatusSnapshots = statusSnapshots,
        workLifecycle = workLifecycle,
        workSessionQueries = workSessionQueries,
        raidCycleModule = raidCycleModule,
        decisionJournal = decisionJournal,
        actionLifecycleModule = actionLifecycleModule,
    )

    init {
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account())
    }

    @Test
    fun `created entry is disabled appended and emits durable wake`() {
        val persisted = entry(91L, AutomationType.QUEST)
        val runtime = TypedAutomationRuntimeStateEntity(
            ACCOUNT_ID,
            account(),
            TypedAutomationLifecycle.RUNNING,
            nextAttemptAt = NOW.plusSeconds(1800),
            waitReason = AutomationWaitReason.SCHEDULED,
            warningText = "stale warning",
            lastError = "stale error",
            createdAt = NOW,
            updatedAt = NOW,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList(), listOf(persisted))
        Mockito.`when`(typedQuery.lockRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(entryRepository.save(anyEntry())).thenReturn(persisted)

        val response = service.createEntry(ACCOUNT_ID, CreateAutomationEntryRequest(AutomationType.QUEST))

        assertEquals(listOf(AutomationType.QUEST), response.entries.map { it.type })
        assertFalse(response.entries.single().enabled)
        assertEquals(0, response.entries.single().priority)
        assertNull(runtime.nextAttemptAt)
        assertNull(runtime.waitReason)
        assertNull(runtime.warningText)
        assertNull(runtime.lastError)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun `aggregate exposes the persisted wait reason`() {
        val runtime = TypedAutomationRuntimeStateEntity(
            ACCOUNT_ID,
            account(),
            TypedAutomationLifecycle.RUNNING,
            nextAttemptAt = NOW.plusSeconds(30),
            waitReason = AutomationWaitReason.HOF_CONNECTION,
            createdAt = NOW,
            updatedAt = NOW,
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)

        assertEquals(
            AutomationWaitReason.HOF_CONNECTION,
            service.getTyped(ACCOUNT_ID).runtime.waitReason,
        )
    }

    @Test
    fun `typed aggregate includes the latest observed HOF status`() {
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())
        Mockito.`when`(statusSnapshots.findLatest(ACCOUNT_ID)).thenReturn(observedStatus(5700))

        assertEquals(5700, service.getTyped(ACCOUNT_ID).hofStatus?.timeCurrent)
    }

    @Test
    fun `typed aggregate allows an account without an observation`() {
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        assertNull(service.getTyped(ACCOUNT_ID).hofStatus)
    }

    @Test
    fun `entry type is a singleton and reorder requires every owned id`() {
        val quest = entry(91L, AutomationType.QUEST)
        val battle = entry(92L, AutomationType.BATTLE_MAP, priority = 1)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest))
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            assertFailsWith<ApiException> {
                service.createEntry(ACCOUNT_ID, CreateAutomationEntryRequest(AutomationType.QUEST))
            }.errorCode,
        )

        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest, battle))
        listOf(listOf(91L, 91L), listOf(91L), listOf(91L, 999L)).forEach { ids ->
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                assertFailsWith<ApiException> {
                    service.reorderEntries(ACCOUNT_ID, ReorderAutomationEntriesRequest(ids))
                }.errorCode,
            )
        }
    }

    @Test
    fun `battle and adventure map entries may be created more than once`() {
        val firstBattle = entry(91L, AutomationType.BATTLE_MAP)
        val secondBattle = entry(92L, AutomationType.BATTLE_MAP, priority = 1)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID))
            .thenReturn(listOf(firstBattle), listOf(firstBattle, secondBattle))
        Mockito.`when`(entryRepository.save(anyEntry())).thenReturn(secondBattle)

        val response = service.createEntry(ACCOUNT_ID, CreateAutomationEntryRequest(AutomationType.BATTLE_MAP))

        assertEquals(listOf(91L, 92L), response.entries.map { it.id })
        assertEquals(listOf(AutomationType.BATTLE_MAP, AutomationType.BATTLE_MAP), response.entries.map { it.type })
    }

    @Test
    fun `entry id update changes only the selected battle map group`() {
        val first = entry(91L, AutomationType.BATTLE_MAP).also {
            it.displayName = "일반"
            it.settingsRevision = 3
        }
        val second = entry(92L, AutomationType.BATTLE_MAP, priority = 1).also {
            it.displayName = "이벤트"
            it.settingsRevision = 7
        }
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, second.id)).thenReturn(second)
        Mockito.`when`(typedQuery.findBattleSettings(second.id)).thenReturn(emptyList())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(first, second))

        val response = service.updateBattleMapGroup(
            ACCOUNT_ID,
            second.id,
            UpdateBattleMapGroupRequest("7", " 보스 ", false, emptyList()),
        )

        assertEquals("일반", response.entries[0].displayName)
        assertEquals("3", response.entries[0].settingsRevision)
        assertEquals("보스", response.entries[1].displayName)
        assertEquals("8", response.entries[1].settingsRevision)
    }

    @Test
    fun `stale entry id save changes nothing and reports a conflict`() {
        val group = entry(92L, AutomationType.BATTLE_MAP).also { it.settingsRevision = 7 }
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, group.id)).thenReturn(group)

        val error = assertFailsWith<ApiException> {
            service.updateBattleMapGroup(
                ACCOUNT_ID,
                group.id,
                UpdateBattleMapGroupRequest("6", "덮어쓸 이름", false, emptyList()),
            )
        }

        assertEquals(ErrorCode.AUTOMATION_SETTINGS_CONFLICT, error.errorCode)
        assertNull(group.displayName)
        assertEquals(7, group.settingsRevision)
        Mockito.verifyNoInteractions(battleSettingRepository)
    }

    @Test
    fun `stale map move changes neither group`() {
        val source = entry(91L, AutomationType.BATTLE_MAP).also { it.settingsRevision = 4 }
        val target = entry(92L, AutomationType.BATTLE_MAP, priority = 1).also { it.settingsRevision = 8 }
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, source.id)).thenReturn(source)
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, target.id)).thenReturn(target)

        val error = assertFailsWith<ApiException> {
            service.moveMapBetweenGroups(
                ACCOUNT_ID,
                target.id,
                MoveMapBetweenGroupsRequest(source.id, "3", "8", "battle_map", "map-1", 0),
            )
        }

        assertEquals(ErrorCode.AUTOMATION_SETTINGS_CONFLICT, error.errorCode)
        assertEquals(4, source.settingsRevision)
        assertEquals(8, target.settingsRevision)
        Mockito.verifyNoInteractions(battleSettingRepository)
        Mockito.verifyNoInteractions(workLifecycle)
    }

    @Test
    fun `legacy type update rejects ambiguous map groups as a conflict`() {
        val first = entry(91L, AutomationType.BATTLE_MAP)
        val second = entry(92L, AutomationType.BATTLE_MAP, priority = 1)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(first, second))

        val error = assertFailsWith<ApiException> {
            service.updateBattleMaps(ACCOUNT_ID, UpdateBattleMapAutomationRequest(false, emptyList()))
        }

        assertEquals(ErrorCode.AUTOMATION_SETTINGS_CONFLICT, error.errorCode)
        Mockito.verifyNoInteractions(battleSettingRepository)
    }

    @Test
    fun `map group cannot be enabled empty or reuse a map from another group`() {
        val first = entry(91L, AutomationType.BATTLE_MAP)
        val second = entry(92L, AutomationType.BATTLE_MAP, priority = 1)
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, second.id)).thenReturn(second)

        assertEquals(
            ErrorCode.INVALID_REQUEST,
            assertFailsWith<ApiException> {
                service.updateBattleMapGroup(
                    ACCOUNT_ID,
                    second.id,
                    UpdateBattleMapGroupRequest("0", null, true, emptyList()),
                )
            }.errorCode,
        )

        val occupied = BattleAutomationMapEntity(
            901L,
            first,
            "battle_map",
            "map-1",
            3,
            PresetSelectionMode.PRIMARY,
            null,
            0,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(first, second))
        Mockito.`when`(typedQuery.findBattleSettingsByEntryIds(listOf(first.id, second.id)))
            .thenReturn(listOf(occupied))

        assertEquals(
            ErrorCode.INVALID_REQUEST,
            assertFailsWith<ApiException> {
                service.updateBattleMapGroup(
                    ACCOUNT_ID,
                    second.id,
                    UpdateBattleMapGroupRequest(
                        "0",
                        null,
                        false,
                        listOf(BattleMapSettingRequest("battle_map", "map-1", 3, PresetSelectionMode.PRIMARY, null, 0)),
                    ),
                )
            }.errorCode,
        )
        Mockito.verifyNoInteractions(battleMapQueryRepository)
    }

    @Test
    fun `moving a battle map updates both groups atomically and preserves map progress ownership`() {
        val source = entry(91L, AutomationType.BATTLE_MAP, enabled = true).also { it.settingsRevision = 4 }
        val target = entry(92L, AutomationType.BATTLE_MAP, priority = 1).also { it.settingsRevision = 8 }
        val moved = BattleAutomationMapEntity(
            901L,
            source,
            "battle_map",
            "map-1",
            3,
            PresetSelectionMode.PRIMARY,
            null,
            0,
        )
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, source.id)).thenReturn(source)
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, target.id)).thenReturn(target)
        Mockito.`when`(typedQuery.findBattleSettings(source.id)).thenReturn(listOf(moved), emptyList())
        Mockito.`when`(typedQuery.findBattleSettings(target.id)).thenReturn(emptyList(), listOf(moved))
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(source, target))

        val response = service.moveMapBetweenGroups(
            ACCOUNT_ID,
            target.id,
            MoveMapBetweenGroupsRequest(
                sourceEntryId = source.id,
                sourceSettingsRevision = "4",
                targetSettingsRevision = "8",
                categoryId = "battle_map",
                mapCode = "map-1",
                targetExecutionOrder = 0,
            ),
        )

        assertEquals(target.id, moved.entry.id)
        assertFalse(source.enabled)
        assertEquals("5", response.entries.single { it.id == source.id }.settingsRevision)
        assertEquals("9", response.entries.single { it.id == target.id }.settingsRevision)
        assertEquals(emptyList(), response.entries.single { it.id == source.id }.battleMaps)
        assertEquals(listOf("map-1"), response.entries.single { it.id == target.id }.battleMaps.map { it.mapCode })
        Mockito.verify(workLifecycle).stopForConfigurationChange(
            ACCOUNT_ID,
            source.id,
            setOf("battle_map/map-1"),
            false,
        )
    }

    @Test
    fun `updates hide foreign typed entry as not found`() {
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        val error = assertFailsWith<ApiException> {
            service.updateBattleMaps(ACCOUNT_ID, UpdateBattleMapAutomationRequest(false, emptyList()))
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        Mockito.verifyNoInteractions(battleSettingRepository)
    }

    @Test
    fun `quest update rejects duplicate source and per-mission execution orders before reference lookup`() {
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry(91L, AutomationType.QUEST)))
        val duplicateSource = UpdateQuestAutomationRequest(
            false,
            listOf(
                questSelection("Q-1", true, 0, emptyList()),
                questSelection("Q-2", true, 0, emptyList()),
            ),
        )
        val duplicateMapOrder = UpdateQuestAutomationRequest(
            false,
            listOf(
                questSelection(
                    "Q-1",
                    true,
                    0,
                    listOf(
                        questMap("m1", 0),
                        questMap("m1", 0).copy(mapCode = "other"),
                    ),
                ),
            ),
        )

        listOf(duplicateSource, duplicateMapOrder).forEach { request ->
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                assertFailsWith<ApiException> { service.updateQuest(ACCOUNT_ID, request) }.errorCode,
            )
        }
        Mockito.verifyNoInteractions(battleMapQueryRepository)
    }

    @Test
    fun `quest update rejects keys that do not match the display code and name`() {
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry(91L, AutomationType.QUEST)))
        val valid = questSelection("0351", true, 0, emptyList(), "마을 지하 수로")
        val invalidSelections = listOf(
            valid.copy(questName = "다른 이름"),
            valid.copy(questKey = "351"),
        )

        invalidSelections.forEach { selection ->
            val error = assertFailsWith<ApiException> {
                service.updateQuest(ACCOUNT_ID, UpdateQuestAutomationRequest(false, listOf(selection)))
            }
            assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        }

        Mockito.verifyNoInteractions(battleMapQueryRepository)
    }

    @Test
    fun `quest update permits the same execution order across different missions`() {
        val questEntry = entry(91L, AutomationType.QUEST)
        val selection = questSelection(
            "Q-1",
            true,
            0,
            listOf(
                questMap("m1", 0).copy(mapCode = "shared"),
                questMap("m2", 0).copy(mapCode = "shared"),
            ),
        )
        val savedSelection = QuestAutomationSelectionEntity(
            301L,
            questEntry,
            selection.questKey,
            true,
            0,
            selection.displayCode,
            selection.questName,
        )
        val request = UpdateQuestAutomationRequest(
            false,
            listOf(selection),
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(questEntry))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                setOf("battle_map" to "shared"),
            ),
        ).thenReturn(listOf(battleMap(1L, "battle_map", "shared")))
        Mockito.`when`(questSelectionRepository.save(anyQuestSelection())).thenReturn(savedSelection)

        service.updateQuest(ACCOUNT_ID, request)

        Mockito.verify(questMapRepository, Mockito.times(2)).save(anyQuestMap())
    }

    @Test
    fun `battle update rejects adventure union and raid categories but permits unrelated substrings`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battleEntry))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("raid" to "map")),
        ).thenReturn(listOf(battleMap(80L, "raid", "map")))
        listOf("adventure_map", "union", "raid").forEach { categoryId ->
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                assertFailsWith<ApiException> {
                    service.updateBattleMaps(
                        ACCOUNT_ID,
                        UpdateBattleMapAutomationRequest(
                            false,
                            listOf(BattleMapSettingRequest(categoryId, "map", 1, PresetSelectionMode.PRIMARY, null, 0)),
                        ),
                    )
                }.errorCode,
            )
        }

        val requested = setOf("scenario_union" to "scenario", "reunion_event" to "unrelated")
        Mockito.`when`(battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(requested)).thenReturn(
            listOf(
                battleMap(81L, "scenario_union", "scenario"),
                battleMap(82L, "reunion_event", "unrelated"),
            ),
        )
        service.updateBattleMaps(
            ACCOUNT_ID,
            UpdateBattleMapAutomationRequest(
                false,
                listOf(
                    BattleMapSettingRequest("scenario_union", "scenario", 1, PresetSelectionMode.PRIMARY, null, 0),
                    BattleMapSettingRequest("reunion_event", "unrelated", 1, PresetSelectionMode.PRIMARY, null, 1),
                ),
            ),
        )

        Mockito.verify(battleSettingRepository, Mockito.times(2)).save(anyBattleSetting())
    }

    @Test
    fun `changing current battle target stops only that target session`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP)
        val old = BattleAutomationMapEntity(
            id = 401L,
            entry = battleEntry,
            categoryId = "battle_map",
            mapCode = "map-1",
            dailyTargetCount = 30,
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        val request = UpdateBattleMapAutomationRequest(
            enabled = true,
            maps = listOf(
                BattleMapSettingRequest(
                    categoryId = "battle_map",
                    mapCode = "map-1",
                    dailyTargetCount = 40,
                    presetMode = PresetSelectionMode.PRIMARY,
                    partyPresetId = null,
                    executionOrder = 0,
                ),
            ),
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battleEntry))
        Mockito.`when`(typedQuery.findBattleSettings(battleEntry.id)).thenReturn(listOf(old))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("battle_map" to "map-1")),
        ).thenReturn(listOf(battleMap(1L, "battle_map", "map-1")))

        service.updateBattleMaps(ACCOUNT_ID, request)

        Mockito.verify(workLifecycle).stopForConfigurationChange(
            ACCOUNT_ID,
            battleEntry.id,
            setOf("battle_map/map-1"),
            false,
        )
    }

    @Test
    fun `changing current quest selection stops that quest session`() {
        val questEntry = entry(91L, AutomationType.QUEST).also { it.enabled = true }
        val selection = questSelection("Q-1", false, 0, emptyList())
        val old = QuestAutomationSelectionEntity(
            301L,
            questEntry,
            selection.questKey,
            true,
            0,
            selection.displayCode,
            selection.questName,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(questEntry))
        Mockito.`when`(typedQuery.findQuestSelections(questEntry.id)).thenReturn(listOf(old))
        Mockito.`when`(typedQuery.findQuestMaps(listOf(old.id))).thenReturn(emptyList())
        Mockito.`when`(questSelectionRepository.save(anyQuestSelection())).thenAnswer { invocation -> invocation.arguments[0] }

        service.updateQuest(
            ACCOUNT_ID,
            UpdateQuestAutomationRequest(
                enabled = true,
                quests = listOf(selection),
            ),
        )

        Mockito.verify(workLifecycle).stopForConfigurationChange(
            ACCOUNT_ID,
            questEntry.id,
            setOf(selection.questKey),
            false,
        )
    }

    @Test
    fun `changing current adventure target stops that map session`() {
        val adventureEntry = entry(93L, AutomationType.ADVENTURE_MAP).also { it.enabled = true }
        val old = AdventureAutomationMapEntity(
            id = 501L,
            entry = adventureEntry,
            categoryId = "adventure_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(adventureEntry))
        Mockito.`when`(typedQuery.findAdventureSettings(adventureEntry.id)).thenReturn(listOf(old))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("adventure_map" to "map-1")),
        ).thenReturn(listOf(battleMap(1L, "adventure_map", "map-1")))

        service.updateAdventureMaps(
            ACCOUNT_ID,
            UpdateAdventureMapAutomationRequest(
                enabled = false,
                maps = emptyList(),
            ),
        )

        Mockito.verify(workLifecycle).stopForConfigurationChange(
            ACCOUNT_ID,
            adventureEntry.id,
            setOf("adventure_map/map-1"),
            true,
        )
    }

    @Test
    fun `home quest update stores selected quests in source order`() {
        val homeEntry = entry(97L, AutomationType.HOME_QUEST)
        val persisted = HomeQuestAutomationSelectionEntity(
            id = 904L,
            entry = homeEntry,
            questId = "home-1",
            questName = "[HQ1] 빗자루 제작",
            enabled = true,
            sourceOrder = 0,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(homeEntry))
        Mockito.`when`(typedQuery.findHomeQuestSelections(homeEntry.id)).thenReturn(emptyList(), listOf(persisted))

        val response = service.updateHomeQuests(
            ACCOUNT_ID,
            UpdateHomeQuestAutomationRequest(
                enabled = true,
                quests = listOf(HomeQuestSelectionRequest("home-1", "[HQ1] 빗자루 제작", true, 4)),
            ),
        )

        Mockito.verify(homeQuestSelectionRepository).save(anyHomeQuestSelection())
        assertEquals(listOf("home-1"), response.entries.single().homeQuests.map { it.questId })
        assertEquals(0, response.entries.single().homeQuests.single().sourceOrder)
        assertTrue(homeEntry.enabled)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun `fishing update stores a preset for each observed battle map`() {
        val fishingEntry = entry(94L, AutomationType.FISHING)
        val persisted = FishingAutomationMapEntity(
            id = 901L,
            entry = fishingEntry,
            categoryId = "battle_map",
            mapCode = "fish-1",
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(fishingEntry))
        Mockito.`when`(typedQuery.findFishingMaps(fishingEntry.id)).thenReturn(emptyList(), listOf(persisted))
        Mockito.`when`(battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("battle_map" to "fish-1")))
            .thenReturn(listOf(battleMap(73L, "battle_map", "fish-1", "거대 잉어")))

        val response = service.updateFishing(
            ACCOUNT_ID,
            UpdateFishingAutomationRequest(
                enabled = true,
                maps = listOf(FishingMapSettingRequest(
                    categoryId = "battle_map",
                    mapCode = "fish-1",
                    presetMode = PresetSelectionMode.PRIMARY,
                    partyPresetId = null,
                    executionOrder = 0,
                )),
            ),
        )

        Mockito.verify(fishingMapRepository).save(anyFishingMap())
        assertEquals("fish-1", response.entries.single().fishingMaps.single().mapCode)
        assertEquals("거대 잉어", response.entries.single().fishingMaps.single().displayName)
        assertTrue(fishingEntry.enabled)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun `union update stores only ordered union maps`() {
        val unionEntry = entry(95L, AutomationType.UNION)
        val persisted = UnionAutomationMapEntity(
            id = 902L,
            entry = unionEntry,
            categoryId = "union",
            mapCode = "union-1",
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(unionEntry))
        Mockito.`when`(typedQuery.findUnionSettings(unionEntry.id)).thenReturn(emptyList(), listOf(persisted))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("union" to "union-1")),
        ).thenReturn(listOf(battleMap(71L, "union", "union-1")))

        val response = service.updateUnion(
            ACCOUNT_ID,
            UpdateUnionAutomationRequest(
                enabled = true,
                maps = listOf(
                    UnionMapSettingRequest(
                        categoryId = "union",
                        mapCode = "union-1",
                        presetMode = PresetSelectionMode.PRIMARY,
                        partyPresetId = null,
                        executionOrder = 0,
                    ),
                ),
            ),
        )

        Mockito.verify(unionSettingRepository).save(anyUnionSetting())
        assertEquals(listOf("union-1"), response.entries.single().unionMaps.map { it.mapCode })
        assertTrue(response.entries.single().enabled)
    }

    @Test
    fun `raid update stores ordered raid targets with their presets`() {
        val raidEntry = entry(96L, AutomationType.RAID)
        val persisted = RaidAutomationTargetEntity(
            id = 903L,
            entry = raidEntry,
            raidId = "raid-1",
            displayName = "첫 번째 레이드",
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(raidEntry))
        Mockito.`when`(typedQuery.findRaidTargets(raidEntry.id)).thenReturn(emptyList(), listOf(persisted))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("raid" to "raid-1")),
        ).thenReturn(listOf(battleMap(72L, "raid", "raid-1", "첫 번째 레이드")))

        val response = service.updateRaid(
            ACCOUNT_ID,
            UpdateRaidAutomationRequest(
                enabled = true,
                targets = listOf(
                    RaidTargetSettingRequest(
                        raidId = "raid-1",
                        displayName = "첫 번째 레이드",
                        presetMode = PresetSelectionMode.PRIMARY,
                        partyPresetId = null,
                        executionOrder = 0,
                    ),
                ),
            ),
        )

        Mockito.verify(raidTargetRepository).save(anyRaidTarget())
        assertEquals(listOf("raid-1"), response.entries.single().raidTargets.map { it.raidId })
        assertTrue(response.entries.single().enabled)
    }

    @Test
    fun `removing an active raid target hands the cycle to manual control`() {
        val raidEntry = entry(96L, AutomationType.RAID, enabled = true)
        val active = RaidAutomationCycleEntity(
            id = 904L,
            account = account(),
            entry = raidEntry,
            raidId = "raid-1",
            raidName = "첫 번째 레이드",
            status = RaidAutomationCycleStatus.IN_BATTLE,
            startedAt = NOW,
            updatedAt = NOW,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(raidEntry))
        Mockito.`when`(typedQuery.findOpenRaidCycle(ACCOUNT_ID)).thenReturn(active)
        val outcome = RaidCycleOutcome(raidEntry.id, active.raidId, RaidCycleOutcomeKind.HANDED_OFF_MANUAL)
        Mockito.`when`(raidCycleModule.recordObservedResult(
            ACCOUNT_ID,
            RaidAttempt(96L, RaidIntentKind.REFRESH, "raid-1", null),
            RaidResultObservation.ManualHandoff,
        )).thenReturn(RaidRecordResult.Recorded(outcome))

        service.updateRaid(
            ACCOUNT_ID,
            UpdateRaidAutomationRequest(enabled = false, targets = emptyList()),
        )

        Mockito.verify(raidCycleModule).recordObservedResult(
            ACCOUNT_ID,
            RaidAttempt(96L, RaidIntentKind.REFRESH, "raid-1", null),
            RaidResultObservation.ManualHandoff,
        )
        Mockito.verify(decisionJournal).appendRaidCycleOutcome(ACCOUNT_ID, outcome)
    }

    @Test
    fun `deleting a raid entry hands its open cycle to manual control`() {
        val raidEntry = entry(96L, AutomationType.RAID, enabled = false)
        val active = RaidAutomationCycleEntity(
            id = 905L,
            account = account(),
            entry = raidEntry,
            raidId = "raid-1",
            raidName = "첫 번째 레이드",
            status = RaidAutomationCycleStatus.REGISTERED_WAITING,
            startedAt = NOW,
            updatedAt = NOW,
        )
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, raidEntry.id)).thenReturn(raidEntry)
        Mockito.`when`(typedQuery.findOpenRaidCycle(ACCOUNT_ID)).thenReturn(active)

        service.deleteEntry(ACCOUNT_ID, raidEntry.id)

        Mockito.verify(raidCycleModule).recordObservedResult(
            ACCOUNT_ID,
            RaidAttempt(96L, RaidIntentKind.REFRESH, "raid-1", null),
            RaidResultObservation.ManualHandoff,
        )
        Mockito.verify(entryRepository).delete(raidEntry)
    }

    @Test
    fun `비활성화된 레이드의 활성 전투 복구 경고도 aggregate에 계속 노출한다`() {
        val raidEntry = entry(96L, AutomationType.RAID, enabled = false)
        val active = RaidAutomationCycleEntity(
            id = 906L,
            account = account(),
            entry = raidEntry,
            raidId = "raid-1",
            raidName = "첫 번째 레이드",
            status = RaidAutomationCycleStatus.IN_BATTLE,
            battleRecoveryChainId = "recovery-1",
            battleRecoveryOriginalExecutionIdentity = "execution-1",
            battleRecoveryLatestExecutionIdentity = "execution-2",
            battleRecoveryFirstAmbiguousAt = NOW,
            battleRecoveryLastSubmittedAt = NOW.plusSeconds(300),
            battleRecoveryRetransmissionCount = 1,
            battleRecoveryNextCheckAt = NOW.plusSeconds(600),
            battleRecoveryCategoryId = "raid",
            battleRecoveryMapCode = "raid001",
            battleRecoverySubmittedFromRunnable = true,
            battleRecoveryLastObservation = RaidBattleRecoveryObservation.RESULT_UNOBSERVED,
            startedAt = NOW,
            updatedAt = NOW,
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(raidEntry))
        Mockito.`when`(typedQuery.findOpenRaidCycle(ACCOUNT_ID)).thenReturn(active)

        val response = service.getTyped(ACCOUNT_ID)

        assertTrue(response.runtime.warnings.any { warning ->
            warning.contains("레이드 전투 결과 미확정") && warning.contains("재전송 1회")
        })
    }

    @Test
    fun `deleting entry stops all of its open work sessions`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP)
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, battleEntry.id)).thenReturn(battleEntry)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        service.deleteEntry(ACCOUNT_ID, battleEntry.id, "0")

        Mockito.verify(workLifecycle).stopForConfigurationChange(
            ACCOUNT_ID,
            battleEntry.id,
            emptySet(),
            true,
        )
    }

    @Test
    fun `stale map group delete changes nothing`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP).also { it.settingsRevision = 4 }
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, battleEntry.id)).thenReturn(battleEntry)

        val error = assertFailsWith<ApiException> {
            service.deleteEntry(ACCOUNT_ID, battleEntry.id, "3")
        }

        assertEquals(ErrorCode.AUTOMATION_SETTINGS_CONFLICT, error.errorCode)
        Mockito.verify(entryRepository, Mockito.never()).delete(battleEntry)
        Mockito.verifyNoInteractions(workLifecycle)
    }

    @Test
    fun `map group delete cancels only an unsubmitted prepared action`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP)
        val prepared = actionRow("BATTLE_MAP", "prepared-delete", battleEntry).also {
            it.status = TypedAutomationActionStatus.PREPARED
        }
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, battleEntry.id)).thenReturn(battleEntry)
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(prepared)
        Mockito.`when`(typedQuery.lockTypedAction(prepared.id)).thenReturn(prepared)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        service.deleteEntry(ACCOUNT_ID, battleEntry.id, "0")

        assertEquals(TypedAutomationActionStatus.FAILED, prepared.status)
        assertEquals(NOW, prepared.finishedAt)
        Mockito.verify(entryRepository).delete(battleEntry)
    }

    @Test
    fun `map group delete preserves a submitted action for convergence`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP)
        val submitted = actionRow("BATTLE_MAP", "submitted-delete", battleEntry)
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, battleEntry.id)).thenReturn(battleEntry)
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(submitted)
        Mockito.`when`(typedQuery.lockTypedAction(submitted.id)).thenReturn(submitted)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        service.deleteEntry(ACCOUNT_ID, battleEntry.id, "0")

        assertEquals(TypedAutomationActionStatus.SUBMITTING, submitted.status)
        assertNull(submitted.finishedAt)
        Mockito.verify(entryRepository).delete(battleEntry)
    }

    @Test
    fun `aggregate uses Korea date for battle progress and only exposes it on battle entry`() {
        currentTime = Instant.parse("2026-07-14T15:00:01Z")
        val quest = entry(91L, AutomationType.QUEST)
        val battle = entry(92L, AutomationType.BATTLE_MAP, priority = 1)
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest, battle))
        Mockito.`when`(
            typedQuery.findBattleProgressRows(ACCOUNT_ID, LocalDate.parse("2026-07-15"), "battle_map"),
        ).thenReturn(
            listOf(
                BattleAutomationDailyProgressEntity(
                    account = account(),
                    progressDate = LocalDate.parse("2026-07-15"),
                    categoryId = "battle_map",
                    mapCode = "gb0",
                    source = "battle_map",
                    successfulRuns = 6,
                    updatedAt = NOW,
                ),
            ),
        )

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(emptyList(), response.entries.first().battleMapProgress)
        assertEquals(6, response.entries.last().battleMapProgress.single().successfulRuns)
    }

    @Test
    fun `aggregate exposes the catalog display name for a stored adventure map`() {
        val adventureEntry = entry(93L, AutomationType.ADVENTURE_MAP)
        val setting = AdventureAutomationMapEntity(
            id = 301L,
            entry = adventureEntry,
            categoryId = "adventure_map",
            mapCode = "festival01",
            presetMode = PresetSelectionMode.PRIMARY,
            executionOrder = 0,
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(adventureEntry))
        Mockito.`when`(typedQuery.findAdventureSettings(adventureEntry.id)).thenReturn(listOf(setting))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                setOf("adventure_map" to "festival01"),
            ),
        ).thenReturn(listOf(battleMap(81L, "adventure_map", "festival01", "Arena- 천년제 무투회")))

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals("Arena- 천년제 무투회", response.entries.single().adventureMaps.single().displayName)
    }

    @Test
    fun `stopped network runtime exposes its failed adventure action with a display name`() {
        val account = account()
        val action = TypedAutomationActionRunEntity(
            id = 502L,
            account = account,
            entry = null,
            entryDisplayName = "최우선 모험",
            executionIdentity = "failed-adventure-action",
            actionKind = "ADVENTURE_MAP",
            payloadJson = "{}",
            actionFingerprint = "b".repeat(64),
            status = TypedAutomationActionStatus.FAILED,
            leaseToken = "lease",
            createdAt = NOW,
            updatedAt = NOW,
        )
        val runtime = TypedAutomationRuntimeStateEntity(
            accountId = ACCOUNT_ID,
            account = account,
            lifecycleStatus = TypedAutomationLifecycle.STOPPED,
            stopReason = AutomationStopReason.NETWORK.name,
            stopActionId = action.id,
            createdAt = NOW,
            updatedAt = NOW,
        )
        val stored = StoredTypedAutomationAction(
            91L,
            action.executionIdentity,
            StoredTypedActionPayload.AdventureMap(
                "adventure_map",
                "sp_hunt_1",
                PresetSelectionMode.PRIMARY,
                7L,
                1,
                901L,
                app.spammy.hof.battle.dto.RunBattleRequest(
                    "adventure_map",
                    "sp_hunt_1",
                    listOf("c1"),
                    listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 0)),
                ),
                StoredActionDisplay(mapName = "모험의 숲"),
            ),
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findStoppedTypedAction(ACCOUNT_ID, action.id)).thenReturn(action)
        val managed = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.`when`(actionLifecycleModule.restore(action, ACCOUNT_ID)).thenReturn(managed)
        Mockito.`when`(managed.descriptor).thenReturn(
            AutomationActionDescriptor(
                source = AutomationType.ADVENTURE_MAP,
                storageKind = "ADVENTURE_MAP",
                actionKind = "ADVENTURE_MAP",
                actionLabel = "모험맵",
                context = "모험 맵 전투 · 모험의 숲",
                targetKey = "adventure_map/sp_hunt_1",
                targetName = "모험의 숲",
                display = stored.payload.display,
                battleCount = 1,
            ),
        )

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(AutomationType.ADVENTURE_MAP, response.runtime.currentAction?.source)
        assertEquals("ADVENTURE_MAP", response.runtime.currentAction?.kind)
        assertEquals("모험맵", response.runtime.currentAction?.actionLabel)
        assertEquals("최우선 모험", response.runtime.currentAction?.entryDisplayName)
        assertEquals("모험의 숲", response.runtime.currentAction?.mapName)
        assertEquals(1, response.runtime.currentAction?.battleCount)
    }

    @Test
    fun `current quest battle exposes exact structured display snapshot`() {
        val payload = StoredTypedActionPayload.QuestBattle(
            questKey = "quest-raw-code",
            questCycle = "1",
            missionKey = "mission-raw-key",
            missionType = app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR,
            categoryId = "battle_map",
            mapCode = "tnfh1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 7L,
            battleCount = 1,
            battleRequest = battleRequest("battle_map", "tnfh1"),
            display = StoredActionDisplay(
                questName = "저택 동관 조사(반복)",
                missionLabel = "맵 클리어",
                missionCurrent = 21,
                missionRequired = 25,
                mapName = "동관 응접실",
            ),
        )
        stubActiveAction("QUEST_BATTLE", payload)

        val action = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals(AutomationType.QUEST, action?.source)
        assertEquals("QUEST_BATTLE", action?.kind)
        assertEquals("퀘스트 전투", action?.actionLabel)
        assertEquals("저택 동관 조사(반복)", action?.questName)
        assertEquals("맵 클리어", action?.missionLabel)
        assertEquals(21, action?.missionCurrent)
        assertEquals(25, action?.missionRequired)
        assertEquals("동관 응접실", action?.mapName)
        assertEquals(1, action?.battleCount)
    }

    @Test
    fun `현재 자택 퀘스트 행동은 수명주기 module descriptor를 표시한다`() {
        val runtime = TypedAutomationRuntimeStateEntity(
            accountId = ACCOUNT_ID,
            account = account(),
            lifecycleStatus = TypedAutomationLifecycle.RUNNING,
            createdAt = NOW,
            updatedAt = NOW,
        )
        val row = actionRow("HOME_QUEST", "home-execution")
        val managed = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(row)
        Mockito.`when`(actionLifecycleModule.restore(row, ACCOUNT_ID)).thenReturn(managed)
        Mockito.`when`(managed.descriptor).thenReturn(
            AutomationActionDescriptor(
                source = AutomationType.HOME_QUEST,
                storageKind = "HOME_QUEST",
                actionKind = "HOME_ACCEPT",
                actionLabel = "자택 퀘스트 수락",
                context = "자택 퀘스트 수락 · 빗자루 제작",
                targetKey = "home-1",
                targetName = "빗자루 제작",
                display = StoredActionDisplay(questName = "빗자루 제작"),
            ),
        )

        val action = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals(AutomationType.HOME_QUEST, action?.source)
        assertEquals("HOME_QUEST", action?.kind)
        assertEquals("자택 퀘스트 수락", action?.actionLabel)
        assertEquals("빗자루 제작", action?.questName)
    }

    @Test
    fun `legacy battle payload never promotes raw map code into a display name`() {
        val payload = StoredTypedActionPayload.BattleMap(
            progressDate = LocalDate.parse("2026-07-19"),
            categoryId = "battle_map",
            mapCode = "tnfh1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 7L,
            battleCount = 1,
            battleRequest = battleRequest("battle_map", "tnfh1"),
            display = null,
        )
        stubActiveAction("BATTLE_MAP", payload)

        val action = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals(AutomationType.BATTLE_MAP, action?.source)
        assertEquals("BATTLE_MAP", action?.kind)
        assertEquals("전투맵", action?.actionLabel)
        assertNull(action?.questName)
        assertNull(action?.missionLabel)
        assertNull(action?.missionCurrent)
        assertNull(action?.missionRequired)
        assertNull(action?.mapName)
        assertEquals(1, action?.battleCount)
    }

    @Test
    fun `union battle is exposed as union instead of a generic battle map`() {
        val payload = StoredTypedActionPayload.BattleMap(
            progressDate = LocalDate.parse("2026-07-19"),
            categoryId = "union",
            mapCode = "0003",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 7L,
            battleCount = 1,
            battleRequest = battleRequest("union", "0003"),
            display = StoredActionDisplay(mapName = "도적소탕"),
            source = BattleAutomationActionSource.UNION_AUTOMATION,
        )
        stubActiveAction("BATTLE_MAP", payload)

        val action = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals(AutomationType.UNION, action?.source)
        assertEquals("BATTLE_MAP", action?.kind)
        assertEquals("유니온", action?.actionLabel)
        assertEquals("도적소탕", action?.mapName)
        assertEquals(1, action?.battleCount)
    }

    @Test
    fun `claim accept labels come from the lifecycle descriptor and unknown actions are omitted`() {
        val claim = StoredTypedActionPayload.QuestClaim(
            "claim-raw-code", "claim-raw-action", StoredActionDisplay(questName = "완료할 퀘스트"),
        )
        val accept = StoredTypedActionPayload.QuestAccept(
            "accept-raw-code", "accept-raw-action", StoredActionDisplay(questName = "수락할 퀘스트"),
        )
        stubActiveActions(
            listOf(
                "QUEST_CLAIM" to claim,
                "QUEST_ACCEPT" to accept,
            ),
        )

        val claimAction = service.getTyped(ACCOUNT_ID).runtime.currentAction
        val acceptAction = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals("퀘스트 완료", claimAction?.actionLabel)
        assertEquals("완료할 퀘스트", claimAction?.questName)
        assertNull(claimAction?.battleCount)
        assertEquals("퀘스트 수락", acceptAction?.actionLabel)
        assertEquals("수락할 퀘스트", acceptAction?.questName)
        assertNull(acceptAction?.battleCount)

        val unknownRow = actionRow("FUTURE_ACTION", "future-execution", entry(91L, AutomationType.QUEST))
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(unknownRow)
        Mockito.`when`(actionLifecycleModule.restore(unknownRow, ACCOUNT_ID))
            .thenThrow(IllegalArgumentException("unsupported payload"))

        val unknownAction = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertNull(unknownAction)
    }

    @Test
    fun `disabled entry stays ready while enabled empty entry reports configuration warning`() {
        val quest = entry(91L, AutomationType.QUEST)
        val battle = entry(92L, AutomationType.BATTLE_MAP, enabled = true, priority = 1)
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest, battle))

        val response = service.getTyped(ACCOUNT_ID)

        assertTrue(response.entries.first().ready)
        assertFalse(response.entries.last().ready)
        assertEquals(listOf("전투 맵 설정이 없습니다."), response.runtime.warnings)
    }

    @Test
    fun `parked raid hold warning is attributed to the raid entry`() {
        val raid = entry(93L, AutomationType.RAID, enabled = true)
        val holdMessage = "사용자가 진행 중인 레이드가 끝날 때까지 레이드 자동화를 보류합니다."
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(raid))
        Mockito.`when`(workSessionQueries.findWaiting(ACCOUNT_ID)).thenReturn(listOf(
            AutomationWorkSessionView(
                id = 31L,
                accountId = ACCOUNT_ID,
                entryId = raid.id,
                entryPriority = raid.priority,
                workType = AutomationWorkType.RAID,
                targetKey = "RaidManual",
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                materialName = null,
                nextCheckAt = NOW.plusSeconds(30),
                holdMessage = holdMessage,
            ),
        ))

        val response = service.getTyped(ACCOUNT_ID)

        assertTrue(holdMessage in response.entries.single().warnings)
        assertTrue(holdMessage in response.runtime.warnings)
    }

    @Test
    fun `enabled non-combat quest without configured maps has no combat map warning`() {
        val quest = entry(91L, AutomationType.QUEST, enabled = true)
        val selection = QuestAutomationSelectionEntity(901L, quest, "0091", true, 0)
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest))
        Mockito.`when`(typedQuery.findQuestSelections(quest.id)).thenReturn(listOf(selection))
        Mockito.`when`(typedQuery.findQuestMaps(listOf(selection.id))).thenReturn(emptyList())

        val response = service.getTyped(ACCOUNT_ID)

        assertTrue(response.runtime.warnings.none { it.contains("전투 맵 설정") })
    }

    @Test
    fun `quest automation accepts a configured three member party with two empty slots`() {
        val account = account()
        val quest = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, true, NOW, NOW)
        val selection = QuestAutomationSelectionEntity(901L, quest, "0571", true, 0)
        val preset = PartyPresetEntity(101L, account, "three-member", NOW, NOW)
        val members = (0..4).map { slot ->
            if (slot >= 3) {
                PartyPresetMemberEntity(preset, slot)
            } else {
                val character = CharacterEntity(
                    id = 1_000L + slot,
                    account = account,
                    hofCharacterId = "character-$slot",
                    name = "character-$slot",
                    job = "job",
                    level = 1,
                    patternSlotCount = 1,
                    imageUrl = null,
                    updatedAt = NOW,
                )
                val pattern = CharacterPatternSlotEntity(
                    2_000L + slot,
                    character,
                    (slot + 1).toString(),
                    "pattern",
                    true,
                )
                PartyPresetMemberEntity(preset, slot, character, pattern)
            }
        }
        val map = app.spammy.hof.automation.entity.QuestAutomationMapEntity(
            902L,
            selection,
            "mission",
            "battle_map",
            "map",
            PresetSelectionMode.EXPLICIT,
            preset,
            0,
            true,
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest))
        Mockito.`when`(typedQuery.findQuestSelections(quest.id)).thenReturn(listOf(selection))
        Mockito.`when`(typedQuery.findQuestMaps(listOf(selection.id))).thenReturn(listOf(map))
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(listOf(preset))
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(listOf(preset.id))).thenReturn(members)

        val response = service.getTyped(ACCOUNT_ID)

        assertTrue(response.entries.single().ready)
        assertTrue(response.entries.single().warnings.isEmpty())
    }

    @Test
    fun `manual stop delegates exact stop reason and does not require an action`() {
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        service.stopTyped(ACCOUNT_ID)

        Mockito.verify(lifecycle).stop(ACCOUNT_ID, AutomationStopReason.MANUAL_STOP, "USER_STOP")
    }

    private fun account() = HofAccountEntity(ACCOUNT_ID, "login", "encrypted", NOW)

    private fun entry(
        id: Long,
        type: AutomationType,
        enabled: Boolean = false,
        priority: Int = 0,
    ) = AutomationEntryEntity(id, account(), type, priority, enabled, NOW, NOW)

    private fun battleMap(
        id: Long,
        categoryId: String,
        mapCode: String,
        name: String = mapCode,
    ) = BattleMapEntity(
        id = id,
        categoryId = categoryId,
        mapCode = mapCode,
        name = name,
        normalizedName = name,
        displayOrder = 0,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun questMap(mission: String, order: Int) = QuestMapSettingRequest(
        mission,
        "battle_map",
        mission,
        PresetSelectionMode.PRIMARY,
        null,
        order,
        false,
    )

    private fun questSelection(
        displayCode: String,
        enabled: Boolean,
        sourceOrder: Int,
        maps: List<QuestMapSettingRequest>,
        questName: String = displayCode,
    ): QuestSelectionRequest {
        val identity = QuestIdentityFactory.create(displayCode, questName)
        return QuestSelectionRequest(
            identity.questKey,
            identity.displayCode,
            identity.name,
            enabled,
            sourceOrder,
            maps,
        )
    }

    private fun battleRequest(categoryId: String, mapCode: String) = RunBattleRequest(
        categoryId,
        mapCode,
        listOf("c1"),
        listOf(BattlePatternLoadRequest("c1", 0)),
    )

    private fun stubActiveAction(actionKind: String, payload: StoredTypedActionPayload) {
        stubActiveActions(listOf(actionKind to payload))
    }

    private fun stubActiveActions(actions: List<Pair<String, StoredTypedActionPayload>>) {
        val runtime = TypedAutomationRuntimeStateEntity(
            accountId = ACCOUNT_ID,
            account = account(),
            lifecycleStatus = TypedAutomationLifecycle.RUNNING,
            createdAt = NOW,
            updatedAt = NOW,
        )
        val rows = actions.mapIndexed { index, (kind, _) ->
            actionRow(kind, "execution-$index")
        }
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(
            rows.first(),
            *rows.drop(1).toTypedArray(),
        )
        rows.zip(actions).forEach { (row, action) ->
            val descriptor = when (val payload = action.second) {
                is StoredTypedActionPayload.QuestClaim -> AutomationActionDescriptor(
                    source = AutomationType.QUEST,
                    storageKind = "QUEST_CLAIM",
                    actionKind = "QUEST_CLAIM",
                    actionLabel = "퀘스트 완료",
                    context = "퀘스트 보상 수령 · ${payload.display?.questName ?: payload.questKey}",
                    targetKey = payload.questKey,
                    targetName = payload.display?.questName,
                    display = payload.display,
                )
                is StoredTypedActionPayload.QuestAccept -> AutomationActionDescriptor(
                    source = AutomationType.QUEST,
                    storageKind = "QUEST_ACCEPT",
                    actionKind = "QUEST_ACCEPT",
                    actionLabel = "퀘스트 수락",
                    context = "퀘스트 수락 · ${payload.display?.questName ?: payload.questKey}",
                    targetKey = payload.questKey,
                    targetName = payload.display?.questName,
                    display = payload.display,
                )
                is StoredTypedActionPayload.QuestBattle -> AutomationActionDescriptor(
                    source = AutomationType.QUEST,
                    storageKind = "QUEST_BATTLE",
                    actionKind = "QUEST_BATTLE",
                    actionLabel = "퀘스트 전투",
                    context = "퀘스트 전투 · ${payload.display?.questName ?: payload.questKey}",
                    targetKey = "${payload.categoryId}/${payload.mapCode}",
                    targetName = payload.display?.mapName,
                    display = payload.display,
                    battleCount = payload.battleCount,
                )
                is StoredTypedActionPayload.BattleMap -> AutomationActionDescriptor(
                    source = when (payload.source) {
                        BattleAutomationActionSource.UNION_AUTOMATION -> AutomationType.UNION
                        BattleAutomationActionSource.FISHING_AUTOMATION -> AutomationType.FISHING
                        BattleAutomationActionSource.RAID_AUTOMATION -> AutomationType.RAID
                        else -> AutomationType.BATTLE_MAP
                    },
                    storageKind = "BATTLE_MAP",
                    actionKind = "BATTLE_MAP",
                    actionLabel = when (payload.source) {
                        BattleAutomationActionSource.UNION_AUTOMATION -> "유니온"
                        BattleAutomationActionSource.FISHING_AUTOMATION -> "낚시"
                        BattleAutomationActionSource.RAID_AUTOMATION -> "레이드"
                        else -> "전투맵"
                    },
                    context = "전투 · ${payload.display?.mapName ?: "${payload.categoryId}/${payload.mapCode}"}",
                    targetKey = payload.sourceTargetKey ?: "${payload.categoryId}/${payload.mapCode}",
                    targetName = payload.display?.mapName,
                    display = payload.display,
                    battleCount = payload.battleCount,
                )
                else -> error("Test fixture descriptor is missing for ${payload.kind()}.")
            }
            val managed = Mockito.mock(ManagedAutomationAction::class.java)
            Mockito.`when`(managed.descriptor).thenReturn(descriptor)
            Mockito.`when`(actionLifecycleModule.restore(row, ACCOUNT_ID)).thenReturn(managed)
        }
    }

    private fun actionRow(
        actionKind: String,
        executionIdentity: String,
        actionEntry: AutomationEntryEntity? = null,
    ) = TypedAutomationActionRunEntity(
        id = 500L,
        account = account(),
        entry = actionEntry,
        executionIdentity = executionIdentity,
        actionKind = actionKind,
        payloadJson = "{}",
        actionFingerprint = "a".repeat(64),
        status = TypedAutomationActionStatus.SUBMITTING,
        leaseToken = "lease",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun observedStatus(timeCurrent: Int) = HofObservedStatusResponse(
        playerName = "《얼어붙은 손길》공민이",
        funds = 331_708_318L,
        timeCurrent = timeCurrent,
        timeMax = 6000,
        work = "Nothing",
        auction = "Nothing",
        observedAt = NOW,
    )

    private fun anyEntry(): AutomationEntryEntity =
        Mockito.any(AutomationEntryEntity::class.java) ?: entry(999L, AutomationType.QUEST)

    private fun anyQuestSelection(): QuestAutomationSelectionEntity =
        Mockito.any(QuestAutomationSelectionEntity::class.java)
            ?: QuestAutomationSelectionEntity(999L, entry(999L, AutomationType.QUEST), "matcher", true, 0)

    private fun anyQuestMap(): QuestAutomationMapEntity =
        Mockito.any(QuestAutomationMapEntity::class.java)
            ?: QuestAutomationMapEntity(
                questSelection = QuestAutomationSelectionEntity(999L, entry(999L, AutomationType.QUEST), "matcher", true, 0),
                missionKey = "matcher",
                categoryId = "battle_map",
                mapCode = "matcher",
                presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0,
                manuallyOverridden = false,
            )

    private fun anyHomeQuestSelection(): HomeQuestAutomationSelectionEntity =
        Mockito.any(HomeQuestAutomationSelectionEntity::class.java)
            ?: HomeQuestAutomationSelectionEntity(
                id = 999L,
                entry = entry(999L, AutomationType.HOME_QUEST),
                questId = "matcher",
                questName = "matcher",
                enabled = true,
                sourceOrder = 0,
            )

    private fun anyBattleSetting(): BattleAutomationMapEntity =
        Mockito.any(BattleAutomationMapEntity::class.java)
            ?: BattleAutomationMapEntity(
                entry = entry(999L, AutomationType.BATTLE_MAP),
                categoryId = "battle_map",
                mapCode = "matcher",
                dailyTargetCount = 1,
                presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0,
            )

    private fun anyFishingSetting(): FishingAutomationSettingEntity =
        Mockito.any(FishingAutomationSettingEntity::class.java)
            ?: FishingAutomationSettingEntity(
                entry = entry(999L, AutomationType.FISHING),
                presetMode = PresetSelectionMode.PRIMARY,
            )

    private fun anyFishingMap(): FishingAutomationMapEntity =
        Mockito.any(FishingAutomationMapEntity::class.java)
            ?: FishingAutomationMapEntity(
                entry = entry(999L, AutomationType.FISHING),
                categoryId = "battle_map",
                mapCode = "matcher",
                presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0,
            )

    private fun anyUnionSetting(): UnionAutomationMapEntity =
        Mockito.any(UnionAutomationMapEntity::class.java)
            ?: UnionAutomationMapEntity(
                entry = entry(999L, AutomationType.UNION),
                categoryId = "union",
                mapCode = "matcher",
                presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0,
            )

    private fun anyRaidTarget(): RaidAutomationTargetEntity =
        Mockito.any(RaidAutomationTargetEntity::class.java)
            ?: RaidAutomationTargetEntity(
                entry = entry(999L, AutomationType.RAID),
                raidId = "matcher",
                displayName = "matcher",
                presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0,
            )

    private companion object {
        const val ACCOUNT_ID = 7L
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
