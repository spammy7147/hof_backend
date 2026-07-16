package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.AutomationModuleMapRequest
import app.spammy.hof.automation.dto.AutomationModuleQuestRequest
import app.spammy.hof.automation.dto.CreateAutomationModuleRequest
import app.spammy.hof.automation.dto.ReorderAutomationModulesRequest
import app.spammy.hof.automation.dto.UpdateAutomationModuleRequest
import app.spammy.hof.automation.dto.CreateAutomationEntryRequest
import app.spammy.hof.automation.dto.ReorderAutomationEntriesRequest
import app.spammy.hof.automation.dto.BattleMapSettingRequest
import app.spammy.hof.automation.dto.AdventureMapSettingRequest
import app.spammy.hof.automation.dto.QuestMapSettingRequest
import app.spammy.hof.automation.dto.QuestSelectionRequest
import app.spammy.hof.automation.dto.UpdateAdventureMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateBattleMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateQuestAutomationRequest
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.AdventureDailyRefreshEntity
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.AutomationModuleConfigRepository
import app.spammy.hof.automation.repository.AutomationModuleMapCommandRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestAggregate
import app.spammy.hof.automation.repository.AutomationModuleQuestCommandRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestMapCommandRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationMapCommandRepository
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.automation.repository.AdventureAutomationMapCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.battle.entity.BattleMapEntity
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class UnifiedAutomationServiceTest {
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val profileRepository = Mockito.mock(AutomationProfileRepository::class.java)
    private val moduleConfigRepository = Mockito.mock(AutomationModuleConfigRepository::class.java)
    private val moduleMapRepository = Mockito.mock(AutomationModuleMapCommandRepository::class.java)
    private val moduleQuestRepository = Mockito.mock(AutomationModuleQuestCommandRepository::class.java)
    private val moduleQuestMapRepository = Mockito.mock(AutomationModuleQuestMapCommandRepository::class.java)
    private val jobRepository = Mockito.mock(AutomationJobRepository::class.java)
    private val queryRepository = Mockito.mock(UnifiedAutomationQueryRepository::class.java)
    private val battleMapQueryRepository = Mockito.mock(BattleMapQueryRepository::class.java)
    private val partyPresetQueryRepository = Mockito.mock(PartyPresetQueryRepository::class.java)
    private val readinessEvaluator = AutomationModuleReadinessEvaluator(partyPresetQueryRepository)
    private val afterCommitWakeupService = Mockito.mock(AutomationAfterCommitWakeupService::class.java)
    private val typedQuery = Mockito.mock(TypedAutomationQueryRepository::class.java)
    private val entryRepository = Mockito.mock(AutomationEntryCommandRepository::class.java)
    private val questSelectionRepository = Mockito.mock(QuestAutomationSelectionCommandRepository::class.java)
    private val questMapRepository = Mockito.mock(QuestAutomationMapCommandRepository::class.java)
    private val battleSettingRepository = Mockito.mock(BattleAutomationMapCommandRepository::class.java)
    private val adventureSettingRepository = Mockito.mock(AdventureAutomationMapCommandRepository::class.java)
    private val automationOutbox = Mockito.mock(AutomationOutboxService::class.java)
    private val storedActionCodec = Mockito.mock(StoredTypedAutomationActionCodec::class.java)
    private var currentTime: Instant = NOW
    private val service = UnifiedAutomationService(
        accountQueryRepository = accountQueryRepository,
        profileRepository = profileRepository,
        moduleConfigRepository = moduleConfigRepository,
        moduleMapRepository = moduleMapRepository,
        moduleQuestRepository = moduleQuestRepository,
        moduleQuestMapRepository = moduleQuestMapRepository,
        jobRepository = jobRepository,
        queryRepository = queryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        partyPresetQueryRepository = partyPresetQueryRepository,
        timeProvider = TimeProvider { currentTime },
        afterCommitWakeupService = afterCommitWakeupService,
        readinessEvaluator = readinessEvaluator,
        typedEntryRepository = entryRepository,
        typedQuestSelectionRepository = questSelectionRepository,
        typedQuestMapRepository = questMapRepository,
        typedBattleMapRepository = battleSettingRepository,
        typedAdventureMapRepository = adventureSettingRepository,
        automationOutboxService = automationOutbox,
        typedAutomationQueryRepository = typedQuery,
        storedActionCodec = storedActionCodec,
    )

    init {
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account())
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile())
    }

    @Test
    fun getReturnsNoGeneratedModulesForAnEmptyProfile() {
        val account = account()
        val profile = profile(account = account)
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(null, profile)
        Mockito.`when`(profileRepository.save(anyProfile())).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList())

        val response = service.get(ACCOUNT_ID)

        assertEquals(profile.id, response.profileId)
        assertEquals(emptyList(), response.modules)
        Mockito.verify(moduleConfigRepository, Mockito.never()).saveAll(Mockito.anyList())
        val lockOrder = Mockito.inOrder(accountQueryRepository, queryRepository, profileRepository)
        lockOrder.verify(accountQueryRepository).findByIdForUpdate(ACCOUNT_ID)
        lockOrder.verify(queryRepository).findProfileForUpdate(ACCOUNT_ID)
        lockOrder.verify(profileRepository).save(anyProfile())
        lockOrder.verify(profileRepository).flush()
        lockOrder.verify(queryRepository).findProfileForUpdate(ACCOUNT_ID)
    }

    @Test
    fun createTypedEntryIsDisabledEmptyLastAndEmitsDurableWake() {
        val account = account()
        val persisted = AutomationEntryEntity(
            id = 91L, account = account, type = AutomationType.QUEST, priority = 0, enabled = false,
            createdAt = NOW, updatedAt = NOW,
        )
        val runtime = stoppedNetworkRuntime(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList(), listOf(persisted))
        Mockito.`when`(entryRepository.save(anyTypedEntry())).thenReturn(persisted)
        Mockito.`when`(typedQuery.lockRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)

        val response = service.createEntry(ACCOUNT_ID, CreateAutomationEntryRequest(AutomationType.QUEST))

        assertEquals(listOf(AutomationType.QUEST), response.entries.map { it.type })
        assertFalse(response.entries.single().enabled)
        assertEquals(0, response.entries.single().priority)
        assertTrue(response.entries.single().ready)
        assertEquals(emptyList(), response.entries.single().warnings)
        assertPreservedFailureDiagnostic(response, runtime)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun typedAggregateExposesCurrentActionAndKoreaDailyRefreshStatus() {
        currentTime = Instant.parse("2026-07-16T00:00:00Z")
        val account = account()
        val entry = AutomationEntryEntity(
            id = 91L, account = account, type = AutomationType.BATTLE_MAP, priority = 0, enabled = true,
            createdAt = NOW, updatedAt = NOW,
        )
        val action = TypedAutomationActionRunEntity(
            id = 501L,
            account = account,
            entry = entry,
            executionIdentity = "battle-action-1",
            actionKind = "BATTLE_MAP",
            schemaVersion = 1,
            payloadJson = "{}",
            actionFingerprint = "a".repeat(64),
            status = TypedAutomationActionStatus.SUBMITTING,
            leaseToken = "lease",
            createdAt = NOW,
            updatedAt = NOW,
        )
        val stored = StoredTypedAutomationActionV1(
            entryId = entry.id,
            executionIdentity = action.executionIdentity,
            payload = StoredTypedActionPayload.BattleMap(
                progressDate = LocalDate.parse("2026-07-16"),
                categoryId = "battle",
                mapCode = "Castle202",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 7L,
                battleCount = 3,
                battleRequest = app.spammy.hof.battle.dto.RunBattleRequest(
                    categoryId = "battle",
                    mapCode = "Castle202",
                    characterIds = listOf("c1"),
                    patternLoads = listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 0)),
                    battleCount = 3,
                ),
            ),
        )
        val refresh = AdventureDailyRefreshEntity(
            id = 601L,
            account = account,
            refreshDate = LocalDate.parse("2026-07-16"),
            refreshedAt = NOW.minusSeconds(60),
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry))
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findBattleSettings(entry.id)).thenReturn(emptyList())
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(
            TypedAutomationRuntimeStateEntity(
                accountId = ACCOUNT_ID,
                account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        Mockito.`when`(typedQuery.findActiveTypedAction(ACCOUNT_ID)).thenReturn(action)
        Mockito.`when`(storedActionCodec.verifyPersisted(action, ACCOUNT_ID)).thenReturn(stored)
        Mockito.`when`(typedQuery.findLatestAdventureRefresh(ACCOUNT_ID)).thenReturn(refresh)

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(AutomationType.BATTLE_MAP, response.runtime.currentAction?.source)
        assertEquals("Castle202", response.runtime.currentAction?.title)
        assertEquals(1, response.runtime.currentAction?.battleCurrent)
        assertEquals(3, response.runtime.currentAction?.battleTotal)
        assertEquals("COMPLETE", response.runtime.dailyRefresh.status)
        assertEquals("2026-07-16", response.runtime.dailyRefresh.refreshDate)
        assertEquals(refresh.refreshedAt.toString(), response.runtime.dailyRefresh.refreshedAt)
    }

    @Test
    fun stoppedNetworkAggregateUsesItsExactFailedActionAndDerivesItsSourceWithoutAnEntry() {
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
            lastError = "connection reset",
            createdAt = NOW,
            finishedAt = NOW.plusSeconds(1),
            updatedAt = NOW.plusSeconds(1),
        )
        val runtime = stoppedNetworkRuntime(account).apply { stopActionId = action.id }
        val stored = StoredTypedAutomationActionV1(
            entryId = 91L,
            executionIdentity = action.executionIdentity,
            payload = StoredTypedActionPayload.AdventureMap(
                categoryId = "adventure_map",
                mapCode = "sp_hunt_1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 7L,
                battleCount = 1,
                settingIdentity = 901L,
                battleRequest = app.spammy.hof.battle.dto.RunBattleRequest(
                    categoryId = "adventure_map",
                    mapCode = "sp_hunt_1",
                    characterIds = listOf("c1"),
                    patternLoads = listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 0)),
                    battleCount = 1,
                ),
            ),
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findStoppedTypedAction(ACCOUNT_ID, action.id)).thenReturn(action)
        Mockito.`when`(storedActionCodec.verifyPersisted(action, ACCOUNT_ID)).thenReturn(stored)

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(AutomationType.ADVENTURE_MAP, response.runtime.currentAction?.source)
        assertEquals("sp_hunt_1", response.runtime.currentAction?.title)
        assertEquals(1, response.runtime.currentAction?.battleCurrent)
        assertEquals(1, response.runtime.currentAction?.battleTotal)
    }

    @Test
    fun actionlessNetworkStopDoesNotPresentAnOldFailedActionAsCurrent() {
        val account = account()
        val runtime = stoppedNetworkRuntime(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(null, response.runtime.currentAction)
        Mockito.verify(typedQuery, Mockito.never()).findStoppedTypedAction(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun runningAggregateDoesNotPresentAnOldFailedActionAsCurrent() {
        val account = account()
        val runtime = TypedAutomationRuntimeStateEntity(
            ACCOUNT_ID, account, TypedAutomationLifecycle.RUNNING, createdAt = NOW, updatedAt = NOW,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(null, response.runtime.currentAction)
        Mockito.verify(typedQuery, Mockito.never()).findStoppedTypedAction(Mockito.anyLong(), Mockito.anyLong())
    }

    @Test
    fun createTypedEntryRejectsExistingSingletonType() {
        val existing = AutomationEntryEntity(
            id = 91L, account = account(), type = AutomationType.QUEST, priority = 0, enabled = false,
            createdAt = NOW, updatedAt = NOW,
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(existing))

        val error = assertFailsWith<ApiException> {
            service.createEntry(ACCOUNT_ID, CreateAutomationEntryRequest(AutomationType.QUEST))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        Mockito.verify(entryRepository, Mockito.never()).save(anyTypedEntry())
    }

    @Test
    fun reorderTypedEntriesRequiresEveryOwnedIdExactlyOnce() {
        val rows = listOf(
            AutomationEntryEntity(91L, account(), AutomationType.QUEST, 0, false, NOW, NOW),
            AutomationEntryEntity(92L, account(), AutomationType.BATTLE_MAP, 1, false, NOW, NOW),
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(rows)

        assertFailsWith<ApiException> {
            service.reorderEntries(ACCOUNT_ID, ReorderAutomationEntriesRequest(listOf(91L, 91L)))
        }
        assertFailsWith<ApiException> {
            service.reorderEntries(ACCOUNT_ID, ReorderAutomationEntriesRequest(listOf(91L)))
        }
        assertFailsWith<ApiException> {
            service.reorderEntries(ACCOUNT_ID, ReorderAutomationEntriesRequest(listOf(91L, 999L)))
        }
    }

    @Test
    fun deleteTypedEntryClearsOnlyConfigurationWarningBeforeDurableWake() {
        val account = account()
        val target = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, false, NOW, NOW)
        val remaining = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 1, false, NOW, NOW)
        val runtime = stoppedNetworkRuntime(account)
        Mockito.`when`(typedQuery.findEntry(ACCOUNT_ID, target.id)).thenReturn(target)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(remaining))
        Mockito.`when`(typedQuery.lockRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        val response = service.deleteEntry(ACCOUNT_ID, target.id)

        assertEquals(listOf(remaining.id), response.entries.map { it.id })
        assertPreservedFailureDiagnostic(response, runtime)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun reorderTypedEntriesClearsOnlyConfigurationWarningBeforeDurableWake() {
        val account = account()
        val first = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, false, NOW, NOW)
        val second = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 1, false, NOW, NOW)
        val runtime = stoppedNetworkRuntime(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(first, second))
        Mockito.`when`(typedQuery.lockRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        val response = service.reorderEntries(ACCOUNT_ID, ReorderAutomationEntriesRequest(listOf(second.id, first.id)))

        assertEquals(listOf(second.id, first.id), response.entries.sortedBy { it.priority }.map { it.id })
        assertPreservedFailureDiagnostic(response, runtime)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun typedUpdatesRejectDuplicateQuestsAndUnsupportedBattleCategories() {
        val quest = AutomationEntryEntity(91L, account(), AutomationType.QUEST, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest))
        assertFailsWith<ApiException> {
            service.updateQuest(
                ACCOUNT_ID,
                UpdateQuestAutomationRequest(
                    enabled = false,
                    quests = listOf(
                        QuestSelectionRequest(" Q-1 ", true, 0, emptyList()),
                        QuestSelectionRequest("Q-1", true, 1, emptyList()),
                    ),
                ),
            )
        }

        val battle = AutomationEntryEntity(92L, account(), AutomationType.BATTLE_MAP, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battle))
        listOf("adventure_map" to "모험맵", "union" to "유니온").forEach { (categoryId, messagePart) ->
            val error = assertFailsWith<ApiException> {
                service.updateBattleMaps(
                    ACCOUNT_ID,
                    UpdateBattleMapAutomationRequest(
                        enabled = false,
                        maps = listOf(
                            BattleMapSettingRequest(categoryId, "map", 1, PresetSelectionMode.PRIMARY, null, 0),
                        ),
                    ),
                )
            }
            assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
            assertTrue(error.message.contains(messagePart))
        }
        Mockito.verifyNoInteractions(battleMapQueryRepository)
    }

    @Test
    fun typedBattleUpdateDoesNotRejectUnionSubstringsOrUnrelatedCategoryIds() {
        val account = account()
        val battle = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, false, NOW, NOW)
        val maps = listOf(
            battleMap("scenario_union", "scenario"),
            battleMap("reunion_event", "unrelated", id = 82L),
        )
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battle))
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                setOf("scenario_union" to "scenario", "reunion_event" to "unrelated"),
            ),
        ).thenReturn(maps)
        Mockito.`when`(typedQuery.findBattleSettings(battle.id)).thenReturn(emptyList())
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        assertDoesNotThrow {
            service.updateBattleMaps(
                ACCOUNT_ID,
                UpdateBattleMapAutomationRequest(
                    enabled = false,
                    maps = listOf(
                        BattleMapSettingRequest("scenario_union", "scenario", 1, PresetSelectionMode.PRIMARY, null, 0),
                        BattleMapSettingRequest("reunion_event", "unrelated", 1, PresetSelectionMode.PRIMARY, null, 1),
                    ),
                ),
            )
        }

        Mockito.verify(battleSettingRepository, Mockito.times(2)).save(anyTypedBattleSetting())
    }

    @Test
    fun typedUpdateHidesForeignEntryAsNotFound() {
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(emptyList())

        val error = assertFailsWith<ApiException> {
            service.updateBattleMaps(ACCOUNT_ID, UpdateBattleMapAutomationRequest(false, emptyList()))
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        Mockito.verifyNoInteractions(battleSettingRepository)
    }

    @Test
    fun typedAggregateWarnsWhenPrimaryPresetHasNoRunnableCombatMapping() {
        val account = account()
        val entry = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, true, NOW, NOW)
        val primary = preset()
        val setting = BattleAutomationMapEntity(
            id = 71L, entry = entry, categoryId = "battle_map", mapCode = "gb0",
            dailyTargetCount = 3, presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0,
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry))
        Mockito.`when`(typedQuery.findBattleSettings(entry.id)).thenReturn(listOf(setting))
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(listOf(primary))
        Mockito.`when`(partyPresetQueryRepository.findPrimaryByAccountId(ACCOUNT_ID)).thenReturn(primary)
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(listOf(primary.id))).thenReturn(emptyList())

        val response = service.getTyped(ACCOUNT_ID)

        assertFalse(response.entries.single().ready)
        assertTrue(response.entries.single().warnings.any { it.contains("전투") || it.contains("파티") })
    }

    @Test
    fun typedAggregateUsesTheNextKstDateAcrossTheUtcBoundaryAndExposesProgressOnlyOnBattleEntry() {
        currentTime = Instant.parse("2026-07-14T15:00:01Z")
        val account = account()
        val quest = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, false, NOW, NOW)
        val battle = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 1, false, NOW, NOW)
        val progress = listOf(
            BattleAutomationDailyProgressEntity(
                account = account,
                progressDate = LocalDate.parse("2026-07-15"),
                categoryId = "battle_map",
                mapCode = "gb0",
                source = "battle_map",
                successfulRuns = 6,
                updatedAt = NOW,
            ),
            BattleAutomationDailyProgressEntity(
                account = account,
                progressDate = LocalDate.parse("2026-07-15"),
                categoryId = "battle_map",
                mapCode = "deleted-map",
                source = "battle_map",
                successfulRuns = 2,
                updatedAt = NOW,
            ),
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest, battle))
        Mockito.`when`(
            typedQuery.findBattleProgressRows(ACCOUNT_ID, LocalDate.parse("2026-07-15"), "battle_map"),
        ).thenReturn(progress)
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(emptyList(), response.entries.first().battleMapProgress)
        assertEquals(
            listOf(
                Triple("battle_map", "deleted-map", 2),
                Triple("battle_map", "gb0", 6),
            ),
            response.entries.last().battleMapProgress.map { Triple(it.categoryId, it.mapCode, it.successfulRuns) },
        )
        Mockito.verify(typedQuery, Mockito.times(1))
            .findBattleProgressRows(ACCOUNT_ID, LocalDate.parse("2026-07-15"), "battle_map")
    }

    @Test
    fun typedDirectUpdatesRejectNegativeSourceAndExecutionOrders() {
        fun assertInvalid(block: () -> Unit) {
            assertEquals(ErrorCode.INVALID_REQUEST, assertFailsWith<ApiException> { block() }.errorCode)
        }
        val account = account()
        val quest = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest))
        assertInvalid {
            service.updateQuest(
                ACCOUNT_ID,
                UpdateQuestAutomationRequest(false, listOf(QuestSelectionRequest("Q-1", true, -1, emptyList()))),
            )
        }
        assertInvalid {
            service.updateQuest(
                ACCOUNT_ID,
                UpdateQuestAutomationRequest(
                    false,
                    listOf(
                        QuestSelectionRequest(
                            "Q-1", true, 0,
                            listOf(QuestMapSettingRequest("mission", "battle_map", "gb0", PresetSelectionMode.PRIMARY, null, -1, false)),
                        ),
                    ),
                ),
            )
        }

        val battle = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battle))
        assertInvalid {
            service.updateBattleMaps(
                ACCOUNT_ID,
                UpdateBattleMapAutomationRequest(
                    false, listOf(BattleMapSettingRequest("battle_map", "gb0", 1, PresetSelectionMode.PRIMARY, null, -1)),
                ),
            )
        }

        val adventure = AutomationEntryEntity(93L, account, AutomationType.ADVENTURE_MAP, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(adventure))
        assertInvalid {
            service.updateAdventureMaps(
                ACCOUNT_ID,
                UpdateAdventureMapAutomationRequest(
                    false, listOf(AdventureMapSettingRequest("adventure_map", "Noble101", PresetSelectionMode.PRIMARY, null, -1)),
                ),
            )
        }
    }

    @Test
    fun typedAggregateSplitsPersistedWarningsThenAppendsDistinctConfigWarningsInEntryOrder() {
        val account = account()
        val entry = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, true, NOW, NOW)
        val runtime = TypedAutomationRuntimeStateEntity(
            ACCOUNT_ID, account, TypedAutomationLifecycle.RUNNING,
            warningText = " first warning \n\n second warning \n first warning ",
            createdAt = NOW, updatedAt = NOW,
        )
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry))
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        val response = service.getTyped(ACCOUNT_ID)

        assertEquals(
            listOf("first warning", "second warning", "전투 맵 설정이 없습니다."),
            response.runtime.warnings,
        )
    }

    @Test
    fun questUpdateRejectsMoreThanOneHundredTotalMapRowsBeforeReferenceLookupsOrWrites() {
        val entry = AutomationEntryEntity(91L, account(), AutomationType.QUEST, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry))
        fun maps(prefix: String, count: Int) = (0 until count).map { index ->
            QuestMapSettingRequest("$prefix-mission-$index", "battle_map", "$prefix-map-$index", PresetSelectionMode.PRIMARY, null, index, false)
        }

        val error = assertFailsWith<ApiException> {
            service.updateQuest(
                ACCOUNT_ID,
                UpdateQuestAutomationRequest(
                    false,
                    listOf(
                        QuestSelectionRequest("Q-1", true, 0, maps("a", 51)),
                        QuestSelectionRequest("Q-2", true, 1, maps("b", 50)),
                    ),
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        Mockito.verifyNoInteractions(battleMapQueryRepository, questSelectionRepository, questMapRepository)
    }

    @Test
    fun typedUpdatesRejectDuplicateSourceAndExecutionOrdersBeforeReferenceLookups() {
        val account = account()
        fun questMap(mission: String, order: Int) =
            QuestMapSettingRequest(mission, "battle_map", mission, PresetSelectionMode.PRIMARY, null, order, false)
        fun assertInvalid(block: () -> Unit) {
            assertEquals(ErrorCode.INVALID_REQUEST, assertFailsWith<ApiException> { block() }.errorCode)
        }

        val quest = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(quest))
        assertInvalid {
            service.updateQuest(
                ACCOUNT_ID,
                UpdateQuestAutomationRequest(
                    false,
                    listOf(
                        QuestSelectionRequest("Q-1", true, 0, emptyList()),
                        QuestSelectionRequest("Q-2", true, 0, emptyList()),
                    ),
                ),
            )
        }
        assertInvalid {
            service.updateQuest(
                ACCOUNT_ID,
                UpdateQuestAutomationRequest(
                    false,
                    listOf(QuestSelectionRequest("Q-1", true, 0, listOf(questMap("m1", 0), questMap("m2", 0)))),
                ),
            )
        }

        val battle = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(battle))
        assertInvalid {
            service.updateBattleMaps(
                ACCOUNT_ID,
                UpdateBattleMapAutomationRequest(
                    false,
                    listOf(
                        BattleMapSettingRequest("battle_map", "gb0", 1, PresetSelectionMode.PRIMARY, null, 0),
                        BattleMapSettingRequest("battle_map", "gb1", 1, PresetSelectionMode.PRIMARY, null, 0),
                    ),
                ),
            )
        }

        val adventure = AutomationEntryEntity(93L, account, AutomationType.ADVENTURE_MAP, 0, false, NOW, NOW)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(adventure))
        assertInvalid {
            service.updateAdventureMaps(
                ACCOUNT_ID,
                UpdateAdventureMapAutomationRequest(
                    false,
                    listOf(
                        AdventureMapSettingRequest("adventure_map", "Noble101", PresetSelectionMode.PRIMARY, null, 0),
                        AdventureMapSettingRequest("adventure_map", "Noble102", PresetSelectionMode.PRIMARY, null, 0),
                    ),
                ),
            )
        }
        Mockito.verifyNoInteractions(battleMapQueryRepository)
    }

    @Test
    fun repeatedExplicitPresetIsBulkLoadedOnceAndReusedForEverySavedMap() {
        val account = account()
        val entry = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, false, NOW, NOW)
        val first = battleMap("battle_map", "gb0")
        val second = battleMap("battle_map", "gb1", id = 82L)
        val preset = preset()
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry))
        Mockito.`when`(battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("battle_map" to "gb0", "battle_map" to "gb1")))
            .thenReturn(listOf(first, second))
        Mockito.`when`(partyPresetQueryRepository.findOwnedByAccountIdAndIds(ACCOUNT_ID, setOf(preset.id))).thenReturn(listOf(preset))
        val savedSettings = mutableListOf<BattleAutomationMapEntity>()
        Mockito.`when`(battleSettingRepository.save(anyTypedBattleSetting())).thenAnswer { invocation ->
            (invocation.arguments[0] as BattleAutomationMapEntity).also(savedSettings::add)
        }

        service.updateBattleMaps(
            ACCOUNT_ID,
            UpdateBattleMapAutomationRequest(
                false,
                listOf(
                    BattleMapSettingRequest("battle_map", "gb0", 1, PresetSelectionMode.EXPLICIT, preset.id, 0),
                    BattleMapSettingRequest("battle_map", "gb1", 1, PresetSelectionMode.EXPLICIT, preset.id, 1),
                ),
            ),
        )

        Mockito.verify(partyPresetQueryRepository, Mockito.times(1))
            .findOwnedByAccountIdAndIds(ACCOUNT_ID, setOf(preset.id))
        Mockito.verify(partyPresetQueryRepository, Mockito.never()).findOwnedByAccountIdAndId(ACCOUNT_ID, preset.id)
        Mockito.verify(battleSettingRepository, Mockito.times(2)).save(anyTypedBattleSetting())
        assertEquals(listOf(preset.id, preset.id), savedSettings.map { it.partyPreset?.id })
    }

    @Test
    fun disabledEmptyEntryIsReadyAndDoesNotAmplifyRuntimeWarnings() {
        val account = account()
        val disabled = AutomationEntryEntity(91L, account, AutomationType.QUEST, 0, false, NOW, NOW)
        val enabled = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 1, true, NOW, NOW)
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(disabled, enabled))
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        val response = service.getTyped(ACCOUNT_ID)

        assertTrue(response.entries.first().ready)
        assertEquals(emptyList(), response.entries.first().warnings)
        assertFalse(response.entries.last().ready)
        assertEquals(listOf("전투 맵 설정이 없습니다."), response.runtime.warnings)
    }

    @Test
    fun settingsUpdateClearsOnlyConfigurationWarningBeforeDurableWake() {
        val account = account()
        val entry = AutomationEntryEntity(92L, account, AutomationType.BATTLE_MAP, 0, true, NOW, NOW)
        val runtime = stoppedNetworkRuntime(account)
        Mockito.`when`(typedQuery.findEntries(ACCOUNT_ID)).thenReturn(listOf(entry))
        Mockito.`when`(typedQuery.lockRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(typedQuery.findRuntimeState(ACCOUNT_ID)).thenReturn(runtime)
        Mockito.`when`(partyPresetQueryRepository.findAllByAccountId(ACCOUNT_ID)).thenReturn(emptyList())

        val response = service.updateBattleMaps(ACCOUNT_ID, UpdateBattleMapAutomationRequest(false, emptyList()))

        assertPreservedFailureDiagnostic(response, runtime)
        Mockito.verify(automationOutbox).enqueue(ACCOUNT_ID, "SETTINGS_UPDATED")
    }

    @Test
    fun createAppendsDuplicateModuleTypeAtTheFinalPriority() {
        val profile = profile()
        val existing = listOf(
            aggregate(module(profile, id = 11L, priority = 0)),
            aggregate(module(profile, id = 12L, priority = 1)),
        )
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(existing)
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { invocation ->
            copyModule(invocation.arguments[0] as AutomationModuleConfigEntity, id = 31L)
        }

        val response = service.createModule(
            ACCOUNT_ID,
            CreateAutomationModuleRequest(
                displayName = "  야간 Time  ",
                moduleType = AutomationModuleType.TIME_BURN,
                enabled = true,
                thresholdPercent = 90,
            ),
        )

        assertEquals(31L, response.id)
        assertEquals("야간 Time", response.displayName)
        assertEquals(AutomationModuleType.TIME_BURN, response.moduleType)
        assertEquals(2, response.priority)
        assertFalse(response.ready)
        Mockito.verify(queryRepository).findProfileForUpdate(ACCOUNT_ID)
    }

    @Test
    fun createPersistsNormalizedMapsInExecutionOrderAndValidatesPresetOwnership() {
        val profile = profile()
        val firstMap = battleMap("battle_map", "gb0", "고블린")
        val secondMap = battleMap("scenario_ocean", "Sink01", "침수 구역", id = 82L)
        val preset = preset()
        val request = CreateAutomationModuleRequest(
            displayName = "모험 순회",
            moduleType = AutomationModuleType.COOLDOWN_ADVENTURE,
            enabled = true,
            thresholdPercent = null,
            maps = listOf(
                mapRequest(secondMap, preset.id, executionOrder = 1),
                mapRequest(firstMap, preset.id, executionOrder = 0),
            ),
        )
        stubProfileAndCreate(profile, id = 41L)
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                setOf("scenario_ocean" to "Sink01", "battle_map" to "gb0"),
            ),
        ).thenReturn(listOf(secondMap, firstMap))
        Mockito.`when`(partyPresetQueryRepository.findOwnedByAccountIdAndIds(ACCOUNT_ID, setOf(preset.id)))
            .thenReturn(listOf(preset))
        stubExecutablePreset(preset)

        val response = service.createModule(ACCOUNT_ID, request)

        assertEquals(listOf("gb0", "Sink01"), response.maps.map { it.mapCode })
        assertTrue(response.ready)
        val savedMaps = capturedSavedMaps()
        assertEquals(listOf("gb0", "Sink01"), savedMaps.sortedBy { it.executionOrder }.map { it.battleMap.mapCode })
        assertEquals(setOf(preset.id), savedMaps.mapNotNull { it.partyPreset?.id }.toSet())
    }

    @Test
    fun updateReplacesNormalizedChildrenWithoutChangingModuleType() {
        val profile = profile()
        val config = module(profile, id = 51L, type = AutomationModuleType.KEY_QUEST, priority = 0)
        val oldMap = AutomationModuleMapEntity(
            id = 501L,
            moduleConfig = config,
            battleMap = battleMap("battle_map", "old"),
            partyPreset = preset(),
            executionOrder = 0,
        )
        val oldQuest = AutomationModuleQuestEntity(id = 601L, moduleConfig = config, questCode = "old", executionOrder = 0)
        val oldQuestMap = AutomationModuleQuestMapEntity(
            id = 701L,
            moduleQuest = oldQuest,
            battleMap = battleMap("adventure_map", "old-quest", id = 91L),
            partyPreset = preset(),
            executionOrder = 0,
        )
        val targetMap = battleMap("adventure_map", "Noble103", "저택 동관")
        val preset = preset()
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, config.id)).thenReturn(
            AutomationModuleAggregate(
                config = config,
                maps = listOf(oldMap),
                quests = listOf(AutomationModuleQuestAggregate(oldQuest, listOf(oldQuestMap))),
            ),
        )
        Mockito.`when`(battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("adventure_map" to "Noble103")))
            .thenReturn(listOf(targetMap))
        Mockito.`when`(partyPresetQueryRepository.findOwnedByAccountIdAndIds(ACCOUNT_ID, setOf(preset.id)))
            .thenReturn(listOf(preset))
        stubExecutablePreset(preset)
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { it.arguments[0] }
        Mockito.`when`(moduleQuestRepository.save(anyQuest())).thenAnswer { invocation ->
            copyQuest(invocation.arguments[0] as AutomationModuleQuestEntity, id = 801L)
        }

        val response = service.updateModule(
            ACCOUNT_ID,
            config.id,
            UpdateAutomationModuleRequest(
                displayName = "  저택 열쇠  ",
                enabled = true,
                thresholdPercent = null,
                quests = listOf(
                    AutomationModuleQuestRequest(
                        questCode = " 0571 ",
                        executionOrder = 2,
                        maps = listOf(mapRequest(targetMap, preset.id, 0)),
                    ),
                ),
            ),
        )

        assertEquals(AutomationModuleType.KEY_QUEST, response.moduleType)
        assertEquals("저택 열쇠", response.displayName)
        assertEquals(listOf("0571"), response.quests.map { it.questCode })
        assertTrue(response.ready)
        Mockito.verify(moduleQuestMapRepository).deleteAll(listOf(oldQuestMap))
        Mockito.verify(moduleQuestRepository).deleteAll(listOf(oldQuest))
        Mockito.verify(moduleMapRepository).deleteAll(listOf(oldMap))
        Mockito.verify(moduleConfigRepository).save(config)
        Mockito.verify(queryRepository).findProfileForUpdate(ACCOUNT_ID)
    }

    @Test
    fun updateRejectsUnknownModuleWithoutWriting() {
        Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, 999L)).thenReturn(null)

        val failure = assertFailsWith<ApiException> {
            service.updateModule(
                ACCOUNT_ID,
                999L,
                UpdateAutomationModuleRequest("없는 모듈", true, 90),
            )
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure.errorCode)
        Mockito.verifyNoInteractions(moduleMapRepository, moduleQuestRepository, moduleQuestMapRepository)
    }

    @Test
    fun deleteNormalizesRemainingPrioritiesWithoutCancellingTheCurrentJob() {
        val profile = profile()
        val first = aggregate(module(profile, id = 61L, priority = 0))
        val target = aggregate(module(profile, id = 62L, priority = 1))
        val last = aggregate(module(profile, id = 63L, priority = 2))
        val running = job(profile, status = "RUNNING")
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, target.config.id)).thenReturn(target)
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(first, target, last))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(running)

        service.deleteModule(ACCOUNT_ID, target.config.id)

        assertEquals(listOf(0, 1), listOf(first.config.priority, last.config.priority))
        Mockito.verify(moduleConfigRepository).delete(target.config)
        Mockito.verify(moduleConfigRepository).saveAll(listOf(first.config, last.config))
        Mockito.verify(jobRepository, Mockito.never()).delete(running)
        Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
        Mockito.verify(queryRepository).findProfileForUpdate(ACCOUNT_ID)
    }

    @Test
    fun reorderRejectsMissingDuplicateAndForeignModuleIds() {
        val profile = profile()
        val modules = listOf(
            aggregate(module(profile, id = 71L, priority = 0)),
            aggregate(module(profile, id = 72L, priority = 1)),
        )
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(modules)

        listOf(
            listOf(71L),
            listOf(71L, 71L),
            listOf(71L, 999L),
        ).forEach { ids ->
            val failure = assertFailsWith<ApiException> {
                service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(ids))
            }
            assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        }

        Mockito.verify(moduleConfigRepository, Mockito.never()).saveAll(Mockito.anyList())
    }

    @Test
    fun reorderPersistsEveryPriorityAndReturnsTheAuthoritativeOrder() {
        val profile = profile()
        val first = aggregate(module(profile, id = 81L, priority = 0, displayName = "첫째"))
        val second = aggregate(module(profile, id = 82L, priority = 1, displayName = "둘째"))
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(first, second))

        val response = service.reorderModules(
            ACCOUNT_ID,
            ReorderAutomationModulesRequest(listOf(second.config.id, first.config.id)),
        )

        assertEquals(listOf(82L, 81L), response.modules.map { it.id })
        assertEquals(listOf(0, 1), response.modules.map { it.priority })
        Mockito.verify(moduleConfigRepository).saveAll(listOf(second.config, first.config))
        Mockito.verify(queryRepository).findProfileForUpdate(ACCOUNT_ID)
    }

    @Test
    fun createResumesWaitingConfigWhenTheLatestModulesContainAnActiveReadyModule() {
        val profile = profile()
        val ready = readyOtherQuestModule(profile, id = 301L)
        val waiting = job(profile, "WAITING_CONFIG").apply {
            message = "설정이 필요합니다."
            currentAction = "BATTLE:already-running"
            currentModule = "TIME_BURN"
            currentStepIndex = 2
            nextRunAt = NOW.plusSeconds(1_800)
        }
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList(), listOf(ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(waiting)
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { invocation ->
            copyModule(invocation.arguments[0] as AutomationModuleConfigEntity, id = 301L)
        }
        Mockito.`when`(moduleQuestRepository.save(anyQuest())).thenAnswer { it.arguments[0] }

        service.createModule(
            ACCOUNT_ID,
            CreateAutomationModuleRequest(
                displayName = "일반 퀘스트",
                moduleType = AutomationModuleType.OTHER_QUEST,
                enabled = true,
                thresholdPercent = null,
                quests = listOf(AutomationModuleQuestRequest("1001", 0)),
            ),
        )

        assertEquals("RUNNING", waiting.status)
        assertEquals(NOW.plusSeconds(1_800), waiting.nextRunAt)
        assertEquals("설정이 완료되어 자동화를 재개합니다.", waiting.message)
        assertEquals("BATTLE:already-running", waiting.currentAction)
        assertEquals("TIME_BURN", waiting.currentModule)
        assertEquals(2, waiting.currentStepIndex)
        Mockito.verify(jobRepository).save(waiting)
        Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
    }

    @Test
    fun updateResumesWaitingConfigWhenTheEditedModuleBecomesReady() {
        val profile = profile()
        val config = module(profile, id = 311L, type = AutomationModuleType.OTHER_QUEST)
        val waiting = job(profile, "WAITING_CONFIG")
        val ready = aggregate(config, quests = listOf(questAggregate(config, "1001")))
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, config.id)).thenReturn(aggregate(config))
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(waiting)
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { it.arguments[0] }
        Mockito.`when`(moduleQuestRepository.save(anyQuest())).thenAnswer { it.arguments[0] }

        service.updateModule(
            ACCOUNT_ID,
            config.id,
            UpdateAutomationModuleRequest(
                displayName = "완료된 퀘스트",
                enabled = true,
                thresholdPercent = null,
                quests = listOf(AutomationModuleQuestRequest("1001", 0)),
            ),
        )

        assertEquals("RUNNING", waiting.status)
        assertEquals(NOW, waiting.nextRunAt)
        Mockito.verify(jobRepository).save(waiting)
        Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
    }

    @Test
    fun deleteResumesWaitingConfigWhenAnotherActiveReadyModuleRemains() {
        val profile = profile()
        val target = aggregate(module(profile, id = 321L, priority = 0))
        val remaining = readyOtherQuestModule(profile, id = 322L, priority = 1)
        val waiting = job(profile, "WAITING_CONFIG")
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, target.config.id)).thenReturn(target)
        Mockito.`when`(queryRepository.findModules(profile.id))
            .thenReturn(listOf(target, remaining), listOf(remaining))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(waiting)

        service.deleteModule(ACCOUNT_ID, target.config.id)

        assertEquals("RUNNING", waiting.status)
        assertEquals(NOW, waiting.nextRunAt)
        Mockito.verify(jobRepository).save(waiting)
        Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
    }

    @Test
    fun reorderResumesWaitingConfigWhenAnActiveReadyModuleExists() {
        val profile = profile()
        val incomplete = aggregate(module(profile, id = 331L, priority = 0))
        val ready = readyOtherQuestModule(profile, id = 332L, priority = 1)
        val waiting = job(profile, "WAITING_CONFIG")
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(incomplete, ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(waiting)

        service.reorderModules(
            ACCOUNT_ID,
            ReorderAutomationModulesRequest(listOf(ready.config.id, incomplete.config.id)),
        )

        assertEquals("RUNNING", waiting.status)
        assertEquals(NOW, waiting.nextRunAt)
        Mockito.verify(jobRepository).save(waiting)
        Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
    }

    @Test
    fun waitingConfigRemainsBlockedWhenTheLatestModulesAreNotReady() {
        val profile = profile()
        val incomplete = aggregate(module(profile, id = 341L, type = AutomationModuleType.TIME_BURN))
        val waiting = job(profile, "WAITING_CONFIG")
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList(), listOf(incomplete))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(waiting)
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { invocation ->
            copyModule(invocation.arguments[0] as AutomationModuleConfigEntity, id = 341L)
        }

        service.createModule(
            ACCOUNT_ID,
            CreateAutomationModuleRequest("미완성 Time", AutomationModuleType.TIME_BURN, true, 90),
        )

        assertEquals("WAITING_CONFIG", waiting.status)
        assertEquals(null, waiting.nextRunAt)
        Mockito.verify(jobRepository, Mockito.never()).save(waiting)
        Mockito.verifyNoInteractions(afterCommitWakeupService)
    }

    @Test
    fun waitingConfigRemainsBlockedWhenTheAssignedPatternCannotBeLoaded() {
        val profile = profile()
        val preset = preset(342L)
        val config = module(profile, id = 342L, type = AutomationModuleType.TIME_BURN)
        val configured = aggregate(config, maps = listOf(moduleMap(config, preset, "unloadable-map")))
        val waiting = job(profile, "WAITING_CONFIG")
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(configured))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(waiting)
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(setOf(preset.id))).thenReturn(
            listOf(presetMember(preset, withCharacter = true, withPattern = true, canLoad = false)),
        )

        service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(listOf(config.id)))

        assertEquals("WAITING_CONFIG", waiting.status)
        assertEquals(null, waiting.nextRunAt)
        Mockito.verify(jobRepository, Mockito.never()).save(waiting)
        Mockito.verifyNoInteractions(afterCommitWakeupService)
    }

    @Test
    fun moduleMutationRegistersWakeAfterCommitAndKeepsRunningActionUntouched() {
        val profile = profile()
        val ready = readyOtherQuestModule(profile, id = 351L)
        val retryAt = NOW.plusSeconds(3_600)
        val running = job(profile, "RUNNING").apply {
            currentAction = "BATTLE:in-flight"
            currentModule = "TIME_BURN"
            currentStepIndex = 3
            nextRunAt = retryAt
        }
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(running)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()

        try {
            service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(listOf(ready.config.id)))

            Mockito.verifyNoInteractions(afterCommitWakeupService)
            assertEquals("BATTLE:in-flight", running.currentAction)
            assertEquals("TIME_BURN", running.currentModule)
            assertEquals(3, running.currentStepIndex)
            assertEquals(retryAt, running.nextRunAt)
            Mockito.verify(jobRepository, Mockito.never()).save(running)
            val synchronizations = TransactionSynchronizationManager.getSynchronizations()
            assertEquals(1, synchronizations.size)
            synchronizations.forEach { it.afterCommit() }
            Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun moduleMutationMakesAnIdleRunningJobImmediatelyRecoverable() {
        val profile = profile()
        val ready = readyOtherQuestModule(profile, id = 354L)
        val running = job(profile, "RUNNING").apply {
            currentAction = null
            nextRunAt = NOW.plusSeconds(3_600)
            updatedAt = NOW.minusSeconds(60)
        }
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(running)

        service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(listOf(ready.config.id)))

        assertEquals(NOW, running.nextRunAt)
        assertEquals(NOW, running.updatedAt)
        Mockito.verify(jobRepository).save(running)
        Mockito.verify(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
    }

    @Test
    fun afterCommitWakeFailureDoesNotEscapeOrChangeTheCommittedJobState() {
        val profile = profile()
        val ready = readyOtherQuestModule(profile, id = 352L)
        val running = job(profile, "RUNNING").apply {
            currentAction = "BATTLE:in-flight"
            nextRunAt = NOW
        }
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(running)
        Mockito.doThrow(IllegalStateException("delivery failed"))
            .`when`(afterCommitWakeupService).wake(ACCOUNT_ID, "MODULES_UPDATED")
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()

        try {
            service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(listOf(ready.config.id)))
            val synchronizations = TransactionSynchronizationManager.getSynchronizations()

            assertDoesNotThrow { synchronizations.forEach { it.afterCommit() } }
            assertEquals("RUNNING", running.status)
            assertEquals("BATTLE:in-flight", running.currentAction)
            assertEquals(NOW, running.nextRunAt)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun rolledBackModuleMutationDoesNotDeliverTheRegisteredWake() {
        val profile = profile()
        val ready = readyOtherQuestModule(profile, id = 353L)
        val running = job(profile, "RUNNING")
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(ready))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(running)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()

        try {
            service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(listOf(ready.config.id)))
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }

            Mockito.verifyNoInteractions(afterCommitWakeupService)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun createRejectsTheHundredAndFirstModuleWhileReorderAcceptsOneHundredModules() {
        val profile = profile()
        val modules = (0 until 100).map { index ->
            aggregate(module(profile, id = 1_000L + index, priority = index))
        }
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(modules)
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { it.arguments[0] }

        val createFailure = assertFailsWith<ApiException> {
            service.createModule(
                ACCOUNT_ID,
                CreateAutomationModuleRequest("101번째", AutomationModuleType.TIME_BURN, true, 90),
            )
        }
        assertEquals(ErrorCode.INVALID_REQUEST, createFailure.errorCode)
        assertTrue(createFailure.message.contains("최대 100개"))

        val reversedIds = modules.map { it.config.id }.reversed()
        val reordered = service.reorderModules(ACCOUNT_ID, ReorderAutomationModulesRequest(reversedIds))
        assertEquals(reversedIds, reordered.modules.map { it.id })
        assertEquals((0 until 100).toList(), reordered.modules.map { it.priority })
    }

    @Test
    fun createRejectsMoreThanOneHundredChildMapsAcrossAllQuests() {
        val firstQuestMaps = (0 until 100).map { index ->
            AutomationModuleMapRequest("battle_map", "map-$index", null, index)
        }
        val request = CreateAutomationModuleRequest(
            displayName = "과도한 열쇠 퀘스트",
            moduleType = AutomationModuleType.KEY_QUEST,
            enabled = true,
            thresholdPercent = null,
            quests = listOf(
                AutomationModuleQuestRequest("0571", 0, firstQuestMaps),
                AutomationModuleQuestRequest(
                    "0563",
                    1,
                    listOf(AutomationModuleMapRequest("battle_map", "map-extra", null, 0)),
                ),
            ),
        )

        val failure = assertFailsWith<ApiException> { service.createModule(ACCOUNT_ID, request) }

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        assertTrue(failure.message.contains("전체 맵은 최대 100개"))
        Mockito.verifyNoInteractions(battleMapQueryRepository)
    }

    @Test
    fun createRejectsMissingMapAndForeignPreset() {
        val profile = profile()
        val request = CreateAutomationModuleRequest(
            displayName = "모험",
            moduleType = AutomationModuleType.DAILY_ADVENTURE,
            enabled = true,
            thresholdPercent = null,
            maps = listOf(AutomationModuleMapRequest("adventure_map", "missing", 404L, 0)),
        )
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList())
        Mockito.`when`(battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("adventure_map" to "missing")))
            .thenReturn(emptyList())

        val missingMap = assertFailsWith<ApiException> { service.createModule(ACCOUNT_ID, request) }
        assertEquals(ErrorCode.INVALID_REQUEST, missingMap.errorCode)

        val battleMap = battleMap("adventure_map", "missing")
        Mockito.`when`(battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf("adventure_map" to "missing")))
            .thenReturn(listOf(battleMap))
        Mockito.`when`(partyPresetQueryRepository.findOwnedByAccountIdAndIds(ACCOUNT_ID, setOf(404L)))
            .thenReturn(emptyList())

        val foreignPreset = assertFailsWith<ApiException> { service.createModule(ACCOUNT_ID, request) }
        assertEquals(ErrorCode.INVALID_REQUEST, foreignPreset.errorCode)
        Mockito.verify(moduleConfigRepository, Mockito.never()).save(anyModule())
    }

    @Test
    fun createRejectsUnsupportedTypesAndInvalidTypeSpecificSettings() {
        val requests = listOf(
            CreateAutomationModuleRequest("유니온", AutomationModuleType.UNION, true, null),
            CreateAutomationModuleRequest("일반맵", AutomationModuleType.NORMAL_MAP, true, null),
            CreateAutomationModuleRequest("Time", AutomationModuleType.TIME_BURN, true, null),
            CreateAutomationModuleRequest("열쇠", AutomationModuleType.KEY_QUEST, true, null),
            CreateAutomationModuleRequest("쿨다운", AutomationModuleType.COOLDOWN_ADVENTURE, true, null),
            CreateAutomationModuleRequest("일일", AutomationModuleType.DAILY_ADVENTURE, true, null),
            CreateAutomationModuleRequest("퀘스트", AutomationModuleType.OTHER_QUEST, true, null),
        )

        requests.forEach { request ->
            val failure = assertFailsWith<ApiException> { service.createModule(ACCOUNT_ID, request) }
            assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        }
    }

    @Test
    fun createAndUpdateRejectEveryInvalidTypeSpecificFieldCombination() {
        val profile = profile()
        val battleMap = battleMap("battle_map", "gb0", "고블린")
        val selectedMap = mapRequest(battleMap, presetId = null, executionOrder = 0)
        val selectedQuest = AutomationModuleQuestRequest("0571", 0)
        val cases = listOf(
            InvalidTypeSpecificSettingsCase(
                label = "Time 자동 소모의 기준 누락",
                moduleType = AutomationModuleType.TIME_BURN,
                thresholdPercent = null,
                maps = listOf(selectedMap),
                expectedMessagePart = "1%에서 100% 사이",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "Time 자동 소모의 기준 범위 초과",
                moduleType = AutomationModuleType.TIME_BURN,
                thresholdPercent = 101,
                maps = listOf(selectedMap),
                expectedMessagePart = "1%에서 100% 사이",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "Time 자동 소모의 퀘스트",
                moduleType = AutomationModuleType.TIME_BURN,
                thresholdPercent = 90,
                maps = listOf(selectedMap),
                quests = listOf(selectedQuest),
                expectedMessagePart = "퀘스트 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "열쇠 퀘스트의 모듈 맵",
                moduleType = AutomationModuleType.KEY_QUEST,
                thresholdPercent = null,
                maps = listOf(selectedMap),
                quests = listOf(selectedQuest),
                expectedMessagePart = "맵 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "열쇠 퀘스트의 Time 기준",
                moduleType = AutomationModuleType.KEY_QUEST,
                thresholdPercent = 90,
                quests = listOf(selectedQuest),
                expectedMessagePart = "Time 기준 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "열쇠 퀘스트의 퀘스트 누락",
                moduleType = AutomationModuleType.KEY_QUEST,
                thresholdPercent = null,
                expectedMessagePart = "퀘스트를 한 개 이상 선택",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "쿨다운 모험맵의 퀘스트",
                moduleType = AutomationModuleType.COOLDOWN_ADVENTURE,
                thresholdPercent = null,
                maps = listOf(selectedMap),
                quests = listOf(selectedQuest),
                expectedMessagePart = "퀘스트 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "쿨다운 모험맵의 Time 기준",
                moduleType = AutomationModuleType.COOLDOWN_ADVENTURE,
                thresholdPercent = 90,
                maps = listOf(selectedMap),
                expectedMessagePart = "Time 기준 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "쿨다운 모험맵의 맵 누락",
                moduleType = AutomationModuleType.COOLDOWN_ADVENTURE,
                thresholdPercent = null,
                expectedMessagePart = "맵을 한 개 이상 선택",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일일 제한 모험맵의 퀘스트",
                moduleType = AutomationModuleType.DAILY_ADVENTURE,
                thresholdPercent = null,
                maps = listOf(selectedMap),
                quests = listOf(selectedQuest),
                expectedMessagePart = "퀘스트 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일일 제한 모험맵의 Time 기준",
                moduleType = AutomationModuleType.DAILY_ADVENTURE,
                thresholdPercent = 90,
                maps = listOf(selectedMap),
                expectedMessagePart = "Time 기준 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일일 제한 모험맵의 맵 누락",
                moduleType = AutomationModuleType.DAILY_ADVENTURE,
                thresholdPercent = null,
                expectedMessagePart = "맵을 한 개 이상 선택",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일반 퀘스트의 모듈 맵",
                moduleType = AutomationModuleType.OTHER_QUEST,
                thresholdPercent = null,
                maps = listOf(selectedMap),
                quests = listOf(selectedQuest),
                expectedMessagePart = "맵 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일반 퀘스트의 퀘스트별 맵",
                moduleType = AutomationModuleType.OTHER_QUEST,
                thresholdPercent = null,
                quests = listOf(selectedQuest.copy(maps = listOf(selectedMap))),
                expectedMessagePart = "퀘스트별 맵 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일반 퀘스트의 Time 기준",
                moduleType = AutomationModuleType.OTHER_QUEST,
                thresholdPercent = 90,
                quests = listOf(selectedQuest),
                expectedMessagePart = "Time 기준 설정을 제거",
            ),
            InvalidTypeSpecificSettingsCase(
                label = "일반 퀘스트의 퀘스트 누락",
                moduleType = AutomationModuleType.OTHER_QUEST,
                thresholdPercent = null,
                expectedMessagePart = "퀘스트를 한 개 이상 선택",
            ),
        )
        stubInvalidTypeSpecificSettingsPersistence(profile, battleMap)

        cases.forEachIndexed { index, case ->
            assertInvalidTypeSpecificCreate(case)

            val config = module(
                profile = profile,
                id = 1_000L + index,
                type = case.moduleType,
            )
            Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, config.id)).thenReturn(aggregate(config))
            assertInvalidTypeSpecificUpdate(config.id, case)
        }
    }

    @Test
    fun createRejectsDuplicateMapsQuestsAndExecutionOrders() {
        val duplicatedMap = AutomationModuleMapRequest("battle_map", "gb0", 1L, 0)
        val cases = listOf(
            CreateAutomationModuleRequest(
                "중복 맵",
                AutomationModuleType.COOLDOWN_ADVENTURE,
                true,
                null,
                maps = listOf(duplicatedMap, duplicatedMap.copy(executionOrder = 1)),
            ),
            CreateAutomationModuleRequest(
                "중복 맵 순서",
                AutomationModuleType.COOLDOWN_ADVENTURE,
                true,
                null,
                maps = listOf(duplicatedMap, duplicatedMap.copy(mapCode = "gb1")),
            ),
            CreateAutomationModuleRequest(
                "중복 퀘스트",
                AutomationModuleType.KEY_QUEST,
                true,
                null,
                quests = listOf(
                    AutomationModuleQuestRequest("0571", 0, emptyList()),
                    AutomationModuleQuestRequest("0571", 1, emptyList()),
                ),
            ),
            CreateAutomationModuleRequest(
                "중복 퀘스트 순서",
                AutomationModuleType.KEY_QUEST,
                true,
                null,
                quests = listOf(
                    AutomationModuleQuestRequest("0571", 0, emptyList()),
                    AutomationModuleQuestRequest("0563", 0, emptyList()),
                ),
            ),
        )

        cases.forEach { request ->
            val failure = assertFailsWith<ApiException> { service.createModule(ACCOUNT_ID, request) }
            assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        }
    }

    @Test
    fun startRejectsWhenNoEnabledReadyModuleExists() {
        val profile = profile()
        val disabledReady = aggregate(
            module(profile, id = 91L, type = AutomationModuleType.OTHER_QUEST, enabled = false),
            quests = listOf(questAggregate(module(profile), "1001")),
        )
        val enabledIncomplete = aggregate(module(profile, id = 92L, type = AutomationModuleType.TIME_BURN, enabled = true))
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(disabledReady, enabledIncomplete))

        val failure = assertFailsWith<ApiException> { service.start(ACCOUNT_ID) }

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        Mockito.verify(jobRepository, Mockito.never()).save(anyJob())
    }

    @Test
    fun getReportsNotReadyWhenAPresetHasNoAssignedCharacter() {
        val profile = profile()
        val preset = preset(501L)
        val config = module(profile, id = 501L, type = AutomationModuleType.TIME_BURN)
        val configured = aggregate(
            config,
            maps = listOf(moduleMap(config, preset, "empty-preset-map")),
        )
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(profile.account)
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(configured))
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(setOf(preset.id))).thenReturn(
            listOf(presetMember(preset, withCharacter = false, withPattern = false)),
        )

        val response = service.get(ACCOUNT_ID)

        assertFalse(response.modules.single().ready)
        Mockito.verify(partyPresetQueryRepository, Mockito.times(1)).findMembersByPresetIds(setOf(preset.id))
    }

    @Test
    fun startRejectsWhenAnAssignedCharacterHasNoPattern() {
        val profile = profile()
        val preset = preset(502L)
        val config = module(profile, id = 502L, type = AutomationModuleType.TIME_BURN)
        val configured = aggregate(
            config,
            maps = listOf(moduleMap(config, preset, "missing-pattern-map")),
        )
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(profile.account)
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(configured))
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(setOf(preset.id))).thenReturn(
            listOf(presetMember(preset, withCharacter = true, withPattern = false)),
        )

        val failure = assertFailsWith<ApiException> { service.start(ACCOUNT_ID) }

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        Mockito.verify(jobRepository, Mockito.never()).save(anyJob())
        Mockito.verify(partyPresetQueryRepository, Mockito.times(1)).findMembersByPresetIds(setOf(preset.id))
    }

    @Test
    fun startRejectsWhenTheAssignedPatternCannotBeLoaded() {
        val profile = profile()
        val preset = preset(503L)
        val config = module(profile, id = 503L, type = AutomationModuleType.TIME_BURN)
        val configured = aggregate(
            config,
            maps = listOf(moduleMap(config, preset, "unloadable-pattern-map")),
        )
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(profile.account)
        Mockito.`when`(queryRepository.findProfileForUpdate(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(configured))
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(setOf(preset.id))).thenReturn(
            listOf(presetMember(preset, withCharacter = true, withPattern = true, canLoad = false)),
        )

        val failure = assertFailsWith<ApiException> { service.start(ACCOUNT_ID) }

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
        Mockito.verify(jobRepository, Mockito.never()).save(anyJob())
    }

    private fun stubProfileAndCreate(
        profile: AutomationProfileEntity,
        id: Long,
    ) {
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList())
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { invocation ->
            copyModule(invocation.arguments[0] as AutomationModuleConfigEntity, id)
        }
    }

    /**
     * 금지 필드 테스트가 참조 조회나 mock 기본값 때문에 실패하지 않도록 정상 저장 경로를 준비한다.
     * 검증이 누락되면 요청이 끝까지 성공하므로 assertFailsWith가 정확히 회귀를 감지한다.
     */
    private fun stubInvalidTypeSpecificSettingsPersistence(
        profile: AutomationProfileEntity,
        battleMap: BattleMapEntity,
    ) {
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList())
        Mockito.`when`(
            battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(setOf(battleMap.categoryId to battleMap.mapCode)),
        ).thenReturn(listOf(battleMap))
        Mockito.`when`(moduleConfigRepository.save(anyModule())).thenAnswer { it.arguments[0] }
        Mockito.`when`(moduleQuestRepository.save(anyQuest())).thenAnswer { it.arguments[0] }
        Mockito.`when`(profileRepository.save(anyProfile())).thenAnswer { it.arguments[0] }
    }

    /** create와 update가 같은 유형 검증과 사용자 안내 문구를 제공하는지 create 경로에서 확인한다. */
    private fun assertInvalidTypeSpecificCreate(case: InvalidTypeSpecificSettingsCase) {
        val failure = assertFailsWith<ApiException>(case.label) {
            service.createModule(
                ACCOUNT_ID,
                CreateAutomationModuleRequest(
                    displayName = case.label,
                    moduleType = case.moduleType,
                    enabled = true,
                    thresholdPercent = case.thresholdPercent,
                    maps = case.maps,
                    quests = case.quests,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode, case.label)
        assertTrue(failure.message.contains(case.expectedMessagePart), "${case.label}: ${failure.message}")
    }

    /** create와 동일한 금지 필드 규칙이 기존 모듈의 전체 교체 요청에도 적용되는지 확인한다. */
    private fun assertInvalidTypeSpecificUpdate(
        moduleId: Long,
        case: InvalidTypeSpecificSettingsCase,
    ) {
        val failure = assertFailsWith<ApiException>(case.label) {
            service.updateModule(
                ACCOUNT_ID,
                moduleId,
                UpdateAutomationModuleRequest(
                    displayName = case.label,
                    enabled = true,
                    thresholdPercent = case.thresholdPercent,
                    maps = case.maps,
                    quests = case.quests,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode, case.label)
        assertTrue(failure.message.contains(case.expectedMessagePart), "${case.label}: ${failure.message}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun capturedSavedMaps(): List<AutomationModuleMapEntity> {
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<AutomationModuleMapEntity>>
        Mockito.verify(moduleMapRepository).saveAll(capture(captor, emptyList()))
        return captor.value
    }

    private fun <T : Any> capture(
        captor: ArgumentCaptor<T>,
        fallback: T,
    ): T = captor.capture() ?: fallback

    private fun account(id: Long = ACCOUNT_ID) = HofAccountEntity(
        id = id,
        loginId = "account-$id",
        encryptedPassword = "encrypted",
        createdAt = NOW,
    )

    private fun profile(
        id: Long = PROFILE_ID,
        account: HofAccountEntity = account(),
    ) = AutomationProfileEntity(
        id = id,
        account = account,
        name = "통합 자동화",
        mode = UnifiedAutomationQueryRepository.UNIFIED_MODE,
        enabled = true,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun module(
        profile: AutomationProfileEntity,
        id: Long = 10L,
        priority: Int = 0,
        type: AutomationModuleType = AutomationModuleType.TIME_BURN,
        enabled: Boolean = true,
        displayName: String = "자동화",
    ) = AutomationModuleConfigEntity(
        id = id,
        profile = profile,
        moduleType = type,
        enabled = enabled,
        priority = priority,
        displayName = displayName,
        thresholdPercent = if (type == AutomationModuleType.TIME_BURN) 90 else null,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun copyModule(
        source: AutomationModuleConfigEntity,
        id: Long,
    ) = AutomationModuleConfigEntity(
        id = id,
        profile = source.profile,
        moduleType = source.moduleType,
        enabled = source.enabled,
        priority = source.priority,
        displayName = source.displayName,
        thresholdPercent = source.thresholdPercent,
        createdAt = source.createdAt,
        updatedAt = source.updatedAt,
    )

    private fun aggregate(
        config: AutomationModuleConfigEntity,
        maps: List<AutomationModuleMapEntity> = emptyList(),
        quests: List<AutomationModuleQuestAggregate> = emptyList(),
    ) = AutomationModuleAggregate(config, maps, quests)

    private fun questAggregate(
        config: AutomationModuleConfigEntity,
        questCode: String,
    ): AutomationModuleQuestAggregate = AutomationModuleQuestAggregate(
        quest = AutomationModuleQuestEntity(moduleConfig = config, questCode = questCode, executionOrder = 0),
        maps = emptyList(),
    )

    private fun readyOtherQuestModule(
        profile: AutomationProfileEntity,
        id: Long,
        priority: Int = 0,
    ): AutomationModuleAggregate {
        val config = module(
            profile = profile,
            id = id,
            priority = priority,
            type = AutomationModuleType.OTHER_QUEST,
        )
        return aggregate(config, quests = listOf(questAggregate(config, "1001")))
    }

    private fun copyQuest(
        source: AutomationModuleQuestEntity,
        id: Long,
    ) = AutomationModuleQuestEntity(
        id = id,
        moduleConfig = source.moduleConfig,
        questCode = source.questCode,
        executionOrder = source.executionOrder,
    )

    private fun battleMap(
        categoryId: String,
        mapCode: String,
        name: String = mapCode,
        id: Long = 81L,
    ) = BattleMapEntity(
        id = id,
        categoryId = categoryId,
        mapCode = mapCode,
        name = name,
        normalizedName = name,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun preset(id: Long = 101L) = PartyPresetEntity(
        id = id,
        account = account(),
        name = "범용 파티",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun moduleMap(
        config: AutomationModuleConfigEntity,
        preset: PartyPresetEntity,
        mapCode: String,
    ) = AutomationModuleMapEntity(
        moduleConfig = config,
        battleMap = battleMap("battle_map", mapCode),
        partyPreset = preset,
        executionOrder = 0,
    )

    private fun presetMember(
        preset: PartyPresetEntity,
        withCharacter: Boolean,
        withPattern: Boolean,
        canLoad: Boolean = true,
    ): PartyPresetMemberEntity {
        val character = CharacterEntity(
            id = preset.id * 10,
            account = account(),
            hofCharacterId = "character-${preset.id}",
            name = "캐릭터",
            job = "직업",
            updatedAt = NOW,
        ).takeIf { withCharacter }
        val pattern = character?.let {
            CharacterPatternSlotEntity(
                id = preset.id * 100,
                character = it,
                slotCode = "0",
                label = "기본",
                canLoad = canLoad,
            )
        }.takeIf { withPattern }
        return PartyPresetMemberEntity(preset, 0, character, pattern)
    }

    private fun stubExecutablePreset(preset: PartyPresetEntity) {
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(setOf(preset.id))).thenReturn(
            listOf(presetMember(preset, withCharacter = true, withPattern = true)),
        )
    }

    private fun mapRequest(
        map: BattleMapEntity,
        presetId: Long?,
        executionOrder: Int,
    ) = AutomationModuleMapRequest(map.categoryId, map.mapCode, presetId, executionOrder)

    private fun job(
        profile: AutomationProfileEntity,
        status: String,
    ) = AutomationJobEntity(
        id = 201L,
        account = profile.account,
        profile = profile,
        status = status,
        currentStepIndex = 0,
        message = null,
        createdAt = NOW,
        startedAt = NOW,
        updatedAt = NOW,
        finishedAt = null,
    )

    private fun anyProfile(): AutomationProfileEntity =
        Mockito.any(AutomationProfileEntity::class.java) ?: profile()

    private fun anyModule(): AutomationModuleConfigEntity =
        Mockito.any(AutomationModuleConfigEntity::class.java) ?: module(profile())

    private fun anyQuest(): AutomationModuleQuestEntity =
        Mockito.any(AutomationModuleQuestEntity::class.java)
            ?: AutomationModuleQuestEntity(moduleConfig = module(profile()), questCode = "matcher", executionOrder = 0)

    private fun anyJob(): AutomationJobEntity =
        Mockito.any(AutomationJobEntity::class.java) ?: job(profile(), "RUNNING")

    private fun anyTypedEntry(): AutomationEntryEntity =
        Mockito.any(AutomationEntryEntity::class.java)
            ?: AutomationEntryEntity(account = account(), type = AutomationType.QUEST, priority = 0, enabled = false, createdAt = NOW, updatedAt = NOW)

    private fun anyTypedBattleSetting(): BattleAutomationMapEntity =
        Mockito.any(BattleAutomationMapEntity::class.java)
            ?: BattleAutomationMapEntity(
                entry = AutomationEntryEntity(account = account(), type = AutomationType.BATTLE_MAP, priority = 0, enabled = false, createdAt = NOW, updatedAt = NOW),
                categoryId = "battle_map", mapCode = "matcher", dailyTargetCount = 1,
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0,
            )

    private fun stoppedNetworkRuntime(account: HofAccountEntity) = TypedAutomationRuntimeStateEntity(
        ACCOUNT_ID, account, TypedAutomationLifecycle.STOPPED,
        stopReason = AutomationStopReason.NETWORK.name,
        warningText = "stale configuration warning",
        lastError = "network retry diagnostic",
        createdAt = NOW, updatedAt = NOW,
    )

    private fun assertPreservedFailureDiagnostic(
        response: app.spammy.hof.automation.dto.TypedAutomationAggregateResponse,
        runtime: TypedAutomationRuntimeStateEntity,
    ) {
        assertEquals(TypedAutomationLifecycle.STOPPED, response.runtime.lifecycle)
        assertEquals(AutomationStopReason.NETWORK.name, response.runtime.stopReason)
        assertEquals(emptyList(), response.runtime.warnings)
        assertEquals("network retry diagnostic", response.runtime.lastError)
        assertEquals(null, runtime.warningText)
        assertEquals("network retry diagnostic", runtime.lastError)
    }

    private data class InvalidTypeSpecificSettingsCase(
        val label: String,
        val moduleType: AutomationModuleType,
        val thresholdPercent: Int?,
        val maps: List<AutomationModuleMapRequest> = emptyList(),
        val quests: List<AutomationModuleQuestRequest> = emptyList(),
        val expectedMessagePart: String,
    )

    private companion object {
        const val ACCOUNT_ID = 7L
        const val PROFILE_ID = 3L
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
