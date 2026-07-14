package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
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
import org.mockito.Mockito

class LiveAutomationSnapshotLoaderTest {
    private val questGatewayService = Mockito.mock(QuestGatewayService::class.java)
    private val statusService = Mockito.mock(HofStatusService::class.java)
    private val unifiedQueryRepository = Mockito.mock(UnifiedAutomationQueryRepository::class.java)
    private val battleMapQueryRepository = Mockito.mock(BattleMapQueryRepository::class.java)
    private val partyPresetQueryRepository = Mockito.mock(PartyPresetQueryRepository::class.java)
    private val readinessEvaluator = AutomationModuleReadinessEvaluator(partyPresetQueryRepository)
    private val loader = LiveAutomationSnapshotLoader(
        questGatewayService = questGatewayService,
        statusService = statusService,
        unifiedQueryRepository = unifiedQueryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        timeProvider = TimeProvider { NOW },
        readinessEvaluator = readinessEvaluator,
    )

    @Test
    fun `loads every active ready module in persisted priority order including duplicate types`() {
        val second = readyMapModule(202L, AutomationModuleType.TIME_BURN, priority = 8, mapCode = "second")
        val first = readyMapModule(101L, AutomationModuleType.TIME_BURN, priority = 2, mapCode = "first")
        stubSnapshotInputs(
            quests = emptyList(),
            modules = listOf(first, second),
            states = listOf(state(first.maps.single().battleMap), state(second.maps.single().battleMap)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertEquals(listOf(101L, 202L), snapshot.modules.map { it.id })
        assertEquals(listOf("first", "second"), snapshot.modules.map { it.maps.single().mapCode })
        assertEquals(90, snapshot.accountStatus.timeCurrent)
        assertEquals(NOW, snapshot.now)
        Mockito.verify(battleMapQueryRepository).findStatesForExecution(
            ACCOUNT_ID,
            setOf("battle_map" to "first", "battle_map" to "second"),
        )
    }

    @Test
    fun `filters disabled unready and unsupported modules before building the snapshot`() {
        val disabled = readyMapModule(301L, AutomationModuleType.TIME_BURN, 0, "disabled").also {
            it.config.enabled = false
        }
        val unready = readyMapModule(302L, AutomationModuleType.TIME_BURN, 1, "unready")
        val unsupported = aggregate(config(303L, AutomationModuleType.UNION, 2, null))
        stubSnapshotInputs(
            quests = emptyList(),
            modules = listOf(disabled, unready, unsupported),
            states = emptyList(),
            members = listOf(presetMember(unready.maps.single().partyPreset!!, canLoad = false)),
        )

        val snapshot = loader.load(ACCOUNT_ID)

        assertEquals(emptyList(), snapshot.modules)
        Mockito.verify(battleMapQueryRepository).findStatesForExecution(ACCOUNT_ID, emptySet())
    }

    @Test
    fun `preserves each quest module configured quest codes without adding global quests`() {
        val first = questModule(401L, priority = 0, questCodes = listOf("configured-a"))
        val second = questModule(402L, priority = 1, questCodes = listOf("configured-b"))
        val quests = listOf(
            quest("outside", QuestState.CLAIMABLE),
            quest("configured-a", QuestState.ACTIVE),
            quest("configured-b", QuestState.AVAILABLE),
        )
        stubSnapshotInputs(quests, listOf(first, second), emptyList())

        val snapshot = loader.load(ACCOUNT_ID)

        assertEquals(quests, snapshot.questState)
        assertEquals(listOf("configured-a"), snapshot.modules[0].quests.map { it.questCode })
        assertEquals(listOf("configured-b"), snapshot.modules[1].quests.map { it.questCode })
    }

    /** 외부 HOF 응답과 QueryDSL aggregate를 고정해 loader의 batch 조립 규칙만 검증한다. */
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
        val requestedPairs = modules
            .flatMap { module ->
                module.maps.map { it.battleMap.categoryId to it.battleMap.mapCode } +
                    module.quests.flatMap { quest -> quest.maps.map { it.battleMap.categoryId to it.battleMap.mapCode } }
            }
            .toSet()
        Mockito.`when`(battleMapQueryRepository.findStatesForExecution(ACCOUNT_ID, requestedPairs)).thenReturn(states)
        val presets = modules.flatMap { module ->
            module.maps.mapNotNull { it.partyPreset } +
                module.quests.flatMap { quest -> quest.maps.mapNotNull { it.partyPreset } }
        }.distinctBy { it.id }
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(presets.map { it.id }.toSet()))
            .thenReturn(members ?: presets.map { presetMember(it) })
    }

    private fun readyMapModule(
        id: Long,
        type: AutomationModuleType,
        priority: Int,
        mapCode: String,
    ): AutomationModuleAggregate {
        val config = config(id, type, priority, threshold = 90)
        return aggregate(
            config,
            maps = listOf(
                AutomationModuleMapEntity(
                    moduleConfig = config,
                    battleMap = battleMap(mapCode),
                    partyPreset = preset(id),
                    executionOrder = 0,
                ),
            ),
        )
    }

    private fun questModule(id: Long, priority: Int, questCodes: List<String>): AutomationModuleAggregate {
        val config = config(id, AutomationModuleType.OTHER_QUEST, priority, null)
        return aggregate(
            config,
            quests = questCodes.mapIndexed { index, code ->
                AutomationModuleQuestAggregate(
                    AutomationModuleQuestEntity(moduleConfig = config, questCode = code, executionOrder = index),
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
        id: Long,
        type: AutomationModuleType,
        priority: Int,
        threshold: Int?,
    ) = AutomationModuleConfigEntity(
        id = id,
        profile = profile(),
        moduleType = type,
        enabled = true,
        priority = priority,
        displayName = "$type-$priority",
        thresholdPercent = threshold,
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

    private fun preset(id: Long) = PartyPresetEntity(
        id = 1_000L + id,
        account = account(),
        name = "테스트 파티 $id",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun presetMember(preset: PartyPresetEntity, canLoad: Boolean = true): PartyPresetMemberEntity {
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
        )
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
