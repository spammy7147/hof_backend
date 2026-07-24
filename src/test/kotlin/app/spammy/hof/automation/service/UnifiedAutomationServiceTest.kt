package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.BattleMapSettingRequest
import app.spammy.hof.automation.dto.CreateAutomationEntryRequest
import app.spammy.hof.automation.dto.QuestMapSettingRequest
import app.spammy.hof.automation.dto.QuestSelectionRequest
import app.spammy.hof.automation.dto.ReorderAutomationEntriesRequest
import app.spammy.hof.automation.dto.UpdateBattleMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateQuestAutomationRequest
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWaitReason
import app.spammy.hof.automation.entity.AdventureAutomationMapEntity
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
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
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationMapCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
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
    private val entryRepository = Mockito.mock(AutomationEntryCommandRepository::class.java)
    private val questSelectionRepository = Mockito.mock(QuestAutomationSelectionCommandRepository::class.java)
    private val questMapRepository = Mockito.mock(QuestAutomationMapCommandRepository::class.java)
    private val battleSettingRepository = Mockito.mock(BattleAutomationMapCommandRepository::class.java)
    private val adventureSettingRepository = Mockito.mock(AdventureAutomationMapCommandRepository::class.java)
    private val automationOutbox = Mockito.mock(AutomationOutboxService::class.java)
    private val storedActionCodec = Mockito.mock(StoredTypedAutomationActionCodec::class.java)
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
        typedBattleMapRepository = battleSettingRepository,
        typedAdventureMapRepository = adventureSettingRepository,
        automationOutboxService = automationOutbox,
        storedActionCodec = storedActionCodec,
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
                QuestSelectionRequest("Q-1", true, 0, emptyList()),
                QuestSelectionRequest("Q-2", true, 0, emptyList()),
            ),
        )
        val duplicateMapOrder = UpdateQuestAutomationRequest(
            false,
            listOf(
                QuestSelectionRequest(
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
    fun `quest update permits the same execution order across different missions`() {
        val questEntry = entry(91L, AutomationType.QUEST)
        val savedSelection = QuestAutomationSelectionEntity(301L, questEntry, "Q-1", true, 0)
        val request = UpdateQuestAutomationRequest(
            false,
            listOf(
                QuestSelectionRequest(
                    "Q-1",
                    true,
                    0,
                    listOf(
                        questMap("m1", 0).copy(mapCode = "shared"),
                        questMap("m2", 0).copy(mapCode = "shared"),
                    ),
                ),
            ),
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
    fun `battle update rejects adventure and union categories but permits unrelated substrings`() {
        val battleEntry = entry(92L, AutomationType.BATTLE_MAP)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battleEntry))
        listOf("adventure_map", "union").forEach { categoryId ->
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
            executionIdentity = "failed-adventure-action",
            actionKind = "ADVENTURE_MAP",
            schemaVersion = 1,
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
        val stored = StoredTypedAutomationActionV1(
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
        Mockito.`when`(storedActionCodec.verifyPersisted(action, ACCOUNT_ID)).thenReturn(stored)

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(AutomationType.ADVENTURE_MAP, response.runtime.currentAction?.source)
        assertEquals("ADVENTURE_MAP", response.runtime.currentAction?.kind)
        assertEquals("모험맵", response.runtime.currentAction?.actionLabel)
        assertEquals("모험의 숲", response.runtime.currentAction?.mapName)
        assertEquals(1, response.runtime.currentAction?.battleCount)
    }

    @Test
    fun `current quest battle exposes exact structured display snapshot`() {
        val payload = StoredTypedActionPayload.QuestBattle(
            questCode = "quest-raw-code",
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
        assertEquals("전투 진행 중", action?.actionLabel)
        assertNull(action?.questName)
        assertNull(action?.missionLabel)
        assertNull(action?.missionCurrent)
        assertNull(action?.missionRequired)
        assertNull(action?.mapName)
        assertEquals(1, action?.battleCount)
    }

    @Test
    fun `claim accept and unknown action kinds use safe labels without raw identifiers`() {
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
        Mockito.`when`(storedActionCodec.verifyPersisted(unknownRow, ACCOUNT_ID))
            .thenThrow(IllegalArgumentException("unsupported payload"))

        val unknownAction = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals("FUTURE_ACTION", unknownAction?.kind)
        assertEquals("전투 진행 중", unknownAction?.actionLabel)
        assertNull(unknownAction?.questName)
        assertNull(unknownAction?.mapName)

        val displayedUnknownRow = actionRow("FUTURE_ACTION", "displayed-future", entry(91L, AutomationType.QUEST))
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(displayedUnknownRow)
        Mockito.`when`(storedActionCodec.verifyPersisted(displayedUnknownRow, ACCOUNT_ID)).thenReturn(
            StoredTypedAutomationActionV1(
                91L,
                displayedUnknownRow.executionIdentity,
                StoredTypedActionPayload.QuestClaim(
                    "raw-code",
                    "raw-action",
                    StoredActionDisplay(questName = "표시 이름"),
                ),
            ),
        )

        val displayedUnknownAction = service.getTyped(ACCOUNT_ID).runtime.currentAction

        assertEquals("자동화 실행 중", displayedUnknownAction?.actionLabel)
        assertEquals("표시 이름", displayedUnknownAction?.questName)
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
            Mockito.`when`(storedActionCodec.verifyPersisted(row, ACCOUNT_ID)).thenReturn(
                StoredTypedAutomationActionV1(91L, row.executionIdentity, action.second),
            )
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
        schemaVersion = 1,
        payloadJson = "{}",
        actionFingerprint = "a".repeat(64),
        status = TypedAutomationActionStatus.SUBMITTING,
        leaseToken = "lease",
        createdAt = NOW,
        updatedAt = NOW,
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

    private companion object {
        const val ACCOUNT_ID = 7L
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
