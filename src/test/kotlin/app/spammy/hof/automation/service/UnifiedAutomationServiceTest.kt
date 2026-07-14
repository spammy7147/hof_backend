package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.AutomationModuleMapRequest
import app.spammy.hof.automation.dto.AutomationModuleQuestRequest
import app.spammy.hof.automation.dto.CreateAutomationModuleRequest
import app.spammy.hof.automation.dto.ReorderAutomationModulesRequest
import app.spammy.hof.automation.dto.UpdateAutomationModuleRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.AutomationModuleConfigRepository
import app.spammy.hof.automation.repository.AutomationModuleMapCommandRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestAggregate
import app.spammy.hof.automation.repository.AutomationModuleQuestCommandRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestMapCommandRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

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
    private val wakeupPort = Mockito.mock(AutomationWakeupPort::class.java)
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
        timeProvider = TimeProvider { NOW },
        wakeupPort = wakeupPort,
    )

    @Test
    fun getReturnsNoGeneratedModulesForAnEmptyProfile() {
        val account = account()
        val profile = profile(account = account)
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(null)
        Mockito.`when`(accountQueryRepository.findById(ACCOUNT_ID)).thenReturn(account)
        Mockito.`when`(profileRepository.save(anyProfile())).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(emptyList())

        val response = service.get(ACCOUNT_ID)

        assertEquals(profile.id, response.profileId)
        assertEquals(emptyList(), response.modules)
        Mockito.verify(moduleConfigRepository, Mockito.never()).saveAll(Mockito.anyList())
    }

    @Test
    fun createAppendsDuplicateModuleTypeAtTheFinalPriority() {
        val profile = profile()
        val existing = listOf(
            aggregate(module(profile, id = 11L, priority = 0)),
            aggregate(module(profile, id = 12L, priority = 1)),
        )
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
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
        Mockito.`when`(queryRepository.findModule(ACCOUNT_ID, target.config.id)).thenReturn(target)
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(first, target, last))
        Mockito.`when`(queryRepository.findCurrentJob(ACCOUNT_ID)).thenReturn(running)

        service.deleteModule(ACCOUNT_ID, target.config.id)

        assertEquals(listOf(0, 1), listOf(first.config.priority, last.config.priority))
        Mockito.verify(moduleConfigRepository).delete(target.config)
        Mockito.verify(moduleConfigRepository).saveAll(listOf(first.config, last.config))
        Mockito.verify(jobRepository, Mockito.never()).delete(running)
        Mockito.verify(wakeupPort).wake(ACCOUNT_ID, "MODULES_UPDATED")
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
        Mockito.`when`(queryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile)
        Mockito.`when`(queryRepository.findModules(profile.id)).thenReturn(listOf(first, second))

        val response = service.reorderModules(
            ACCOUNT_ID,
            ReorderAutomationModulesRequest(listOf(second.config.id, first.config.id)),
        )

        assertEquals(listOf(82L, 81L), response.modules.map { it.id })
        assertEquals(listOf(0, 1), response.modules.map { it.priority })
        Mockito.verify(moduleConfigRepository).saveAll(listOf(second.config, first.config))
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

    private companion object {
        const val ACCOUNT_ID = 7L
        const val PROFILE_ID = 3L
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
