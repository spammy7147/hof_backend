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
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
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
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList(), listOf(persisted))
        Mockito.`when`(entryRepository.save(anyEntry())).thenReturn(persisted)

        val response = service.createEntry(ACCOUNT_ID, CreateAutomationEntryRequest(AutomationType.QUEST))

        assertEquals(listOf(AutomationType.QUEST), response.entries.map { it.type })
        assertFalse(response.entries.single().enabled)
        assertEquals(0, response.entries.single().priority)
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
    fun `quest update rejects duplicate source and execution orders before reference lookup`() {
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
                        questMap("m2", 0),
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
    fun `stopped network runtime exposes only its exact failed action`() {
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
            ),
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findStoppedTypedAction(ACCOUNT_ID, action.id)).thenReturn(action)
        Mockito.`when`(storedActionCodec.verifyPersisted(action, ACCOUNT_ID)).thenReturn(stored)

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(AutomationType.ADVENTURE_MAP, response.runtime.currentAction?.source)
        assertEquals("sp_hunt_1", response.runtime.currentAction?.title)
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

    private fun battleMap(id: Long, categoryId: String, mapCode: String) = BattleMapEntity(
        id = id,
        categoryId = categoryId,
        mapCode = mapCode,
        name = mapCode,
        normalizedName = mapCode,
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

    private fun anyEntry(): AutomationEntryEntity =
        Mockito.any(AutomationEntryEntity::class.java) ?: entry(999L, AutomationType.QUEST)

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
