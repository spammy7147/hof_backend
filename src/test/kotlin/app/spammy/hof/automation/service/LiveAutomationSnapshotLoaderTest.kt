package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.policy.AdventureMapPolicy
import app.spammy.hof.automation.policy.KeyQuestPolicy
import app.spammy.hof.automation.policy.SelectedQuestPolicy
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.AutomationModuleQuestAggregate
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.dto.HofStatusResponse
import app.spammy.hof.status.service.HofStatusService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.Mockito

class LiveAutomationSnapshotLoaderTest {
    private val questGatewayService = Mockito.mock(QuestGatewayService::class.java)
    private val statusService = Mockito.mock(HofStatusService::class.java)
    private val unifiedQueryRepository = Mockito.mock(UnifiedAutomationQueryRepository::class.java)
    private val battleMapQueryRepository = Mockito.mock(BattleMapQueryRepository::class.java)
    private val keyQuestPolicy = Mockito.mock(KeyQuestPolicy::class.java)
    private val adventureMapPolicy = Mockito.mock(AdventureMapPolicy::class.java)
    private val selectedQuestPolicy = Mockito.mock(SelectedQuestPolicy::class.java)
    private val partyPresetQueryRepository = Mockito.mock(PartyPresetQueryRepository::class.java)
    private val readinessEvaluator = AutomationModuleReadinessEvaluator(partyPresetQueryRepository)
    private val loader = LiveAutomationSnapshotLoader(
        questGatewayService = questGatewayService,
        statusService = statusService,
        unifiedQueryRepository = unifiedQueryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        keyQuestPolicy = keyQuestPolicy,
        adventureMapPolicy = adventureMapPolicy,
        selectedQuestPolicy = selectedQuestPolicy,
        timeProvider = TimeProvider { NOW },
        readinessEvaluator = readinessEvaluator,
    )

    @Test
    fun timeOnlyConfigurationNeverClaimsOrAcceptsAnyQuest() {
        val time = readyMapModule(AutomationModuleType.TIME_BURN, priority = 0, thresholdPercent = 90, mapCode = "time-map")
        stubSnapshotInputs(
            quests = listOf(
                quest("0563", QuestState.CLAIMABLE),
                quest("0999", QuestState.AVAILABLE),
            ),
            modules = listOf(time),
            states = listOf(state(time.maps.single().battleMap)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertNull(snapshot.claimableQuest)
        assertNull(snapshot.acceptablePriorityQuest)
        assertNull(snapshot.claimableQuestModuleType)
        assertNull(snapshot.acceptablePriorityQuestModuleType)
    }

    @Test
    fun configuredQuestCandidatesPreserveModuleAndQuestPriorityAndIgnoreUnconfiguredQuests() {
        val other = questModule(
            type = AutomationModuleType.OTHER_QUEST,
            priority = 1,
            quests = listOf("other-first" to 0, "other-second" to 1),
        )
        val key = readyKeyQuestModule(priority = 5, questCode = "0563", mapCode = "key-map")
        stubSnapshotInputs(
            quests = listOf(
                quest("not-configured", QuestState.CLAIMABLE),
                quest("0563", QuestState.CLAIMABLE),
                quest("other-second", QuestState.AVAILABLE),
                quest("other-first", QuestState.CLAIMABLE),
            ),
            modules = listOf(key, other),
            states = listOf(state(key.quests.single().maps.single().battleMap)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertEquals("other-first", snapshot.claimableQuest?.questId)
        assertEquals(AutomationModuleType.OTHER_QUEST, snapshot.claimableQuestModuleType)
        assertEquals("other-second", snapshot.acceptablePriorityQuest?.questId)
        assertEquals(AutomationModuleType.OTHER_QUEST, snapshot.acceptablePriorityQuestModuleType)
    }

    @Test
    fun compatibilityLoaderSelectsFirstActiveAndReadyModuleAndLoadsMapStatesInOneBatch() {
        val incomplete = aggregate(
            config(type = AutomationModuleType.TIME_BURN, priority = 0, thresholdPercent = 95),
        )
        val complete = readyMapModule(
            type = AutomationModuleType.TIME_BURN,
            priority = 1,
            thresholdPercent = 85,
            mapCode = "ready-time-map",
        )
        val requestedPairs = setOf("battle_map" to "ready-time-map")
        stubSnapshotInputs(
            quests = emptyList(),
            modules = listOf(incomplete, complete),
            states = listOf(state(complete.maps.single().battleMap)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertEquals(85, snapshot.timeThresholdPercent)
        assertEquals("ready-time-map", snapshot.timeMap?.mapCode)
        Mockito.verify(battleMapQueryRepository).findStatesForExecution(ACCOUNT_ID, requestedPairs)
        Mockito.verify(battleMapQueryRepository, Mockito.never())
            .findStateForExecution(Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun compatibilityLoaderSkipsAModuleWhoseAssignedCharacterHasNoPattern() {
        val configured = readyMapModule(
            type = AutomationModuleType.TIME_BURN,
            priority = 0,
            thresholdPercent = 85,
            mapCode = "missing-pattern-map",
        )
        val preset = configured.maps.single().partyPreset!!
        stubSnapshotInputs(
            quests = emptyList(),
            modules = listOf(configured),
            states = emptyList(),
            members = listOf(presetMember(preset, withPattern = false)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertNull(snapshot.timeMap)
        assertEquals(90, snapshot.timeThresholdPercent)
        Mockito.verify(partyPresetQueryRepository, Mockito.times(1)).findMembersByPresetIds(setOf(preset.id))
    }

    @Test
    fun compatibilityLoaderSkipsAModuleWhosePatternCannotBeLoaded() {
        val configured = readyMapModule(
            type = AutomationModuleType.TIME_BURN,
            priority = 0,
            thresholdPercent = 85,
            mapCode = "unloadable-pattern-map",
        )
        val preset = configured.maps.single().partyPreset!!
        stubSnapshotInputs(
            quests = emptyList(),
            modules = listOf(configured),
            states = emptyList(),
            members = listOf(presetMember(preset, withPattern = true, canLoad = false)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertNull(snapshot.timeMap)
        assertEquals(90, snapshot.timeThresholdPercent)
    }

    /** 외부 HOF 조회와 QueryDSL aggregate를 고정해 테스트가 loader의 선택·조립 규칙만 검증하게 한다. */
    private fun stubSnapshotInputs(
        quests: List<QuestSnapshot>,
        modules: List<AutomationModuleAggregate>,
        states: List<AccountBattleMapStateEntity>,
        members: List<PartyPresetMemberEntity>? = null,
    ) {
        Mockito.`when`(questGatewayService.load(ACCOUNT_ID)).thenReturn(quests)
        Mockito.`when`(statusService.fetch(ACCOUNT_ID)).thenReturn(status())
        Mockito.`when`(unifiedQueryRepository.findProfile(ACCOUNT_ID)).thenReturn(profile())
        Mockito.`when`(unifiedQueryRepository.findModules(PROFILE_ID)).thenReturn(modules)
        val pairs = states.map { it.battleMap.categoryId to it.battleMap.mapCode }.toSet()
        Mockito.`when`(battleMapQueryRepository.findStatesForExecution(ACCOUNT_ID, pairs)).thenReturn(states)
        val presets = modules
            .flatMap { module ->
                module.maps.mapNotNull { it.partyPreset } +
                    module.quests.flatMap { quest -> quest.maps.mapNotNull { it.partyPreset } }
            }
            .distinctBy { it.id }
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(presets.map { it.id }.toSet()))
            .thenReturn(members ?: presets.map { presetMember(it, withPattern = true) })
    }

    private fun readyMapModule(
        type: AutomationModuleType,
        priority: Int,
        thresholdPercent: Int?,
        mapCode: String,
    ): AutomationModuleAggregate {
        val config = config(type, priority, thresholdPercent)
        val map = battleMap(mapCode)
        return aggregate(
            config,
            maps = listOf(
                AutomationModuleMapEntity(
                    moduleConfig = config,
                    battleMap = map,
                    partyPreset = preset(),
                    executionOrder = 0,
                ),
            ),
        )
    }

    private fun readyKeyQuestModule(
        priority: Int,
        questCode: String,
        mapCode: String,
    ): AutomationModuleAggregate {
        val config = config(AutomationModuleType.KEY_QUEST, priority, thresholdPercent = null)
        val quest = AutomationModuleQuestEntity(moduleConfig = config, questCode = questCode, executionOrder = 0)
        return aggregate(
            config,
            quests = listOf(
                AutomationModuleQuestAggregate(
                    quest = quest,
                    maps = listOf(
                        AutomationModuleQuestMapEntity(
                            moduleQuest = quest,
                            battleMap = battleMap(mapCode),
                            partyPreset = preset(),
                            executionOrder = 0,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun questModule(
        type: AutomationModuleType,
        priority: Int,
        quests: List<Pair<String, Int>>,
    ): AutomationModuleAggregate {
        val config = config(type, priority, thresholdPercent = null)
        return aggregate(
            config,
            quests = quests.map { (code, order) ->
                AutomationModuleQuestAggregate(
                    AutomationModuleQuestEntity(moduleConfig = config, questCode = code, executionOrder = order),
                    emptyList(),
                )
            },
        )
    }

    private fun aggregate(
        config: AutomationModuleConfigEntity,
        maps: List<AutomationModuleMapEntity> = emptyList(),
        quests: List<AutomationModuleQuestAggregate> = emptyList(),
    ) = AutomationModuleAggregate(config, maps, quests)

    private fun config(
        type: AutomationModuleType,
        priority: Int,
        thresholdPercent: Int?,
    ) = AutomationModuleConfigEntity(
        id = 100L + priority,
        profile = profile(),
        moduleType = type,
        enabled = true,
        priority = priority,
        displayName = "$type-$priority",
        thresholdPercent = thresholdPercent,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun state(map: BattleMapEntity) = AccountBattleMapStateEntity(
        account = account(),
        battleMap = map,
        rawHref = "index.php?common=${map.mapCode}",
        visible = true,
        lastSeenAt = NOW,
    )

    private fun battleMap(code: String) = BattleMapEntity(
        id = code.hashCode().toLong().let { if (it < 0) -it else it } + 1,
        categoryId = "battle_map",
        mapCode = code,
        name = code,
        normalizedName = code,
        enabled = true,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun preset() = PartyPresetEntity(
        id = 301L,
        account = account(),
        name = "테스트 파티",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun presetMember(
        preset: PartyPresetEntity,
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
        )
        val pattern = CharacterPatternSlotEntity(
            id = preset.id * 100,
            character = character,
            slotCode = "0",
            label = "기본",
            canLoad = canLoad,
        ).takeIf { withPattern }
        return PartyPresetMemberEntity(preset, 0, character, pattern)
    }

    private fun profile() = AutomationProfileEntity(
        id = PROFILE_ID,
        account = account(),
        name = "통합 자동화",
        mode = UnifiedAutomationQueryRepository.UNIFIED_MODE,
        enabled = true,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun account() = HofAccountEntity(
        id = ACCOUNT_ID,
        loginId = "loader-account",
        encryptedPassword = "encrypted",
        createdAt = NOW,
    )

    private fun quest(id: String, state: QuestState) = QuestSnapshot(id, id, state, null, "action-$id")

    private fun status() = HofStatusResponse(
        accountId = ACCOUNT_ID,
        playerName = "공민이",
        funds = 100L,
        timeCurrent = 90,
        timeMax = 100,
        work = "Nothing",
        auction = "Nothing",
        observedAt = NOW,
    )

    private companion object {
        const val ACCOUNT_ID = 7L
        const val PROFILE_ID = 3L
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
