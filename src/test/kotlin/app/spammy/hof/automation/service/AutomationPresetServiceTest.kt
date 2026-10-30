package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.dto.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.AutomationPresetQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.lease.AccountAutomationLeaseEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.quest.model.QuestIdentityFactory
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@ActiveProfiles("test")
class AutomationPresetServiceTest {
    @Autowired private lateinit var presets: AutomationPresetService
    @Autowired private lateinit var settings: UnifiedAutomationService
    @Autowired private lateinit var queries: AutomationPresetQueryRepository
    @Autowired private lateinit var work: AutomationWorkSessionQueryRepository
    @Autowired private lateinit var em: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val accountIds = mutableListOf<Long>()
    private val mapCodes = mutableListOf<String>()
    private val now = Instant.now()

    @AfterEach
    fun cleanup() {
        accountIds.forEach { jdbc.update("delete from hof_accounts where id = ?", it) }
        mapCodes.forEach { jdbc.update("delete from battle_maps where map_code = ?", it) }
    }

    @Test
    fun `관측용 맵 이름은 프리셋 설정으로 저장하지 않는다`() {
        val accountId = account()
        val code = map("adventure_map")
        val draft = AutomationPresetSettings(listOf(AutomationPresetEntry(0, AutomationType.ADVENTURE_MAP, true,
            adventureMaps = listOf(AdventureMapSettingResponse("adventure_map", code, PresetSelectionMode.PRIMARY, null, 0, "관측 이름")))))
        val saved = presets.save(accountId, SaveAutomationPresetRequest("모험", draft))
        assertNull(presets.get(accountId, saved.id).settings.entries.single().adventureMaps.single().displayName)
    }

    @Test
    fun `오래된 저장과 적용을 거절하고 저장만으로 현재 적용 표시를 바꾸지 않는다`() {
        val accountId = account()
        val draft = AutomationPresetSettings(listOf(AutomationPresetEntry(0, AutomationType.FISHING, true)))
        val id = presets.save(accountId, SaveAutomationPresetRequest("낚시", draft)).id
        val first = presets.get(accountId, id)
        assertNull(presets.getApplied(accountId))
        presets.save(accountId, SaveAutomationPresetRequest("낚시", draft.copy(entries = listOf(draft.entries.single().copy(enabled = false))), first.revision), id)
        assertFailsWith<ApiException> { presets.save(accountId, SaveAutomationPresetRequest("낚시", draft, first.revision), id) }
        assertFailsWith<ApiException> { presets.apply(accountId, id, first.revision) }
        val latest = presets.get(accountId, id)
        presets.apply(accountId, id, latest.revision)
        assertEquals(id, presets.getApplied(accountId)?.id)
        assertEquals(true, presets.getApplied(accountId)?.matchesCurrent)
        presets.save(accountId, SaveAutomationPresetRequest("낚시", draft, presets.get(accountId, id).revision), id)
        assertEquals(false, presets.getApplied(accountId)?.matchesCurrent)
        assertFalse(settings.getTyped(accountId).entries.single().enabled)
    }

    @Test
    fun `전체 저장은 중복 singleton과 잘못된 맵 설정을 거절하고 현재 설정을 보존한다`() {
        val accountId = account()
        val before = settings.getTyped(accountId)
        val invalid = listOf(
            AutomationPresetSettings(List(2) { AutomationPresetEntry(0, AutomationType.FISHING, false) }),
            AutomationPresetSettings(listOf(AutomationPresetEntry(0, AutomationType.BATTLE_MAP, true))),
            AutomationPresetSettings(listOf(AutomationPresetEntry(0, AutomationType.COLOSSEUM, false, minimumRemainingTime = 50))),
        )
        invalid.forEach { draft ->
            assertFailsWith<ApiException> { presets.save(accountId, SaveAutomationPresetRequest("잘못된 구성", draft)) }
        }
        assertEquals(emptyList(), presets.list(accountId))
        assertEquals(before, settings.getTyped(accountId))
    }

    @Test
    fun `실행 중에도 프리셋 전체 초안을 저장하고 다른 구성과 현재 설정을 보존한다`() {
        val accountId = account()
        settings.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.FISHING))
        transaction {
            em.persist(TypedAutomationRuntimeStateEntity(accountId, em.getReference(HofAccountEntity::class.java, accountId),
                TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
        }
        val before = settings.getTyped(accountId)
        val draft = AutomationPresetSettings(listOf(
            AutomationPresetEntry(0, AutomationType.COLOSSEUM, true),
            AutomationPresetEntry(0, AutomationType.FISHING, true),
        ))
        val first = presets.save(accountId, SaveAutomationPresetRequest("일상", draft))
        val second = presets.save(accountId, SaveAutomationPresetRequest("휴식", AutomationPresetSettings(emptyList())))
        val detail = presets.get(accountId, first.id)
        assertEquals(listOf(AutomationType.COLOSSEUM, AutomationType.FISHING), detail.settings.entries.map { it.type })
        presets.save(accountId, SaveAutomationPresetRequest("일상", draft.copy(entries = draft.entries.reversed()), detail.revision), first.id)
        assertEquals(listOf(AutomationType.FISHING, AutomationType.COLOSSEUM), presets.get(accountId, first.id).settings.entries.map { it.type })
        assertEquals(emptyList(), presets.get(accountId, second.id).settings.entries)
        assertEquals(before, settings.getTyped(accountId))
    }

    @Test
    fun `모든 유형의 설정과 판단 순서를 복원하고 항목 ID와 정지 상태를 유지한다`() {
        val accountId = account()
        val partyId = transaction {
            val party = PartyPresetEntity(account = em.getReference(HofAccountEntity::class.java, accountId),
                name = "전투 파티", createdAt = now, updatedAt = now)
            em.persist(party)
            party.id
        }
        val battleCode = map("battle_map")
        val adventureCode = map("adventure_map")
        val fishingCode = map("battle_map")
        val unionCode = map("union")
        val raidCode = map("raid")
        AutomationType.entries.forEach { settings.createEntry(accountId, CreateAutomationEntryRequest(it)) }
        settings.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(
            QuestSelectionRequest(QuestIdentityFactory.create("q1", "테스트 퀘스트").questKey, "q1", "테스트 퀘스트", true, 0,
                listOf(QuestMapSettingRequest("mission", "battle_map", battleCode, PresetSelectionMode.EXPLICIT, partyId, 0, true))) )))
        settings.updateHomeQuests(accountId, UpdateHomeQuestAutomationRequest(true,
            listOf(HomeQuestSelectionRequest("home1", "자택 퀘스트", true, 0))))
        val battle = settings.getTyped(accountId).entries.single { it.type == AutomationType.BATTLE_MAP }
        settings.updateBattleMapGroup(accountId, battle.id, UpdateBattleMapGroupRequest(battle.settingsRevision, "사냥", true,
            listOf(BattleMapSettingRequest("battle_map", battleCode, 7, PresetSelectionMode.EXPLICIT, partyId, 0)), 1500))
        settings.updateAdventureMaps(accountId, UpdateAdventureMapAutomationRequest(true,
            listOf(AdventureMapSettingRequest("adventure_map", adventureCode, PresetSelectionMode.PRIMARY, null, 0))))
        settings.updateFishing(accountId, UpdateFishingAutomationRequest(true,
            listOf(FishingMapSettingRequest("battle_map", fishingCode, PresetSelectionMode.EXPLICIT, partyId, 0))))
        settings.updateUnion(accountId, UpdateUnionAutomationRequest(true,
            listOf(UnionMapSettingRequest("union", unionCode, PresetSelectionMode.PRIMARY, null, 0))))
        settings.updateRaid(accountId, UpdateRaidAutomationRequest(true,
            listOf(RaidTargetSettingRequest(raidCode, "레이드", PresetSelectionMode.EXPLICIT, partyId, 0))))
        settings.updateColosseum(accountId, UpdateColosseumAutomationRequest(true))
        val expected = settings.getTyped(accountId)
        val preset = presets.save(accountId, SaveAutomationPresetRequest("일상", AutomationPresetSettings.from(expected.entries)))
        settings.updateFishing(accountId, UpdateFishingAutomationRequest(false))
        settings.reorderEntries(accountId, ReorderAutomationEntriesRequest(expected.entries.map { it.id }.reversed()))
        settings.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.BATTLE_MAP))
        val restored = presets.apply(accountId, preset.id, presets.get(accountId, preset.id).revision)
        assertEquals(expected.entries.map { it.copy(settingsRevision = "0") }, restored.entries.map { it.copy(settingsRevision = "0") })
        assertEquals(TypedAutomationLifecycle.STOPPED, restored.runtime.lifecycle)
        val json = transaction { queries.findOwned(accountId, preset.id)!!.settingsJson }
        assertFalse(json.contains("battleMapProgress"))
        assertFalse(json.contains("lifecycle"))
    }

    @Test
    fun `여러 프리셋을 덮어쓰고 삭제하며 계정 소유권과 이름과 개수 제한을 지킨다`() {
        val owner = account()
        val other = account()
        settings.createEntry(owner, CreateAutomationEntryRequest(AutomationType.FISHING))
        val first = presets.save(owner, SaveAutomationPresetRequest("  낚시  "))
        assertEquals("낚시", first.name)
        val error = assertFailsWith<ApiException> { presets.apply(other, first.id, "0") }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        assertFailsWith<ApiException> { presets.save(other, SaveAutomationPresetRequest("침범"), first.id) }
        assertFailsWith<ApiException> { presets.delete(other, first.id) }
        assertEquals(emptyList(), presets.list(other))
        assertFailsWith<ApiException> { presets.save(owner, SaveAutomationPresetRequest("낚시")) }
        assertFailsWith<ApiException> { presets.save(owner, SaveAutomationPresetRequest(" ")) }
        assertFailsWith<ApiException> { presets.save(owner, SaveAutomationPresetRequest("가".repeat(101))) }
        repeat(9) { presets.save(owner, SaveAutomationPresetRequest("설정 $it")) }
        assertFailsWith<ApiException> { presets.save(owner, SaveAutomationPresetRequest("초과")) }
        settings.createEntry(owner, CreateAutomationEntryRequest(AutomationType.COLOSSEUM))
        assertEquals(2, presets.save(owner, SaveAutomationPresetRequest("새 이름", revision = presets.get(owner, first.id).revision), first.id).entryCount)
        assertEquals(9, presets.delete(owner, first.id).size)
        assertEquals(2, settings.getTyped(owner).entries.size)
    }

    @Test
    fun `실행 중과 처리 중인 계정은 설정을 바꾸지 않는다`() {
        val accountId = account()
        val entry = settings.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.FISHING)).entries.single()
        val preset = presets.save(accountId, SaveAutomationPresetRequest("낚시"))
        transaction {
            val account = em.getReference(HofAccountEntity::class.java, accountId)
            em.persist(TypedAutomationRuntimeStateEntity(accountId, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
        }
        assertFailsWith<ApiException> { presets.apply(accountId, preset.id, presets.get(accountId, preset.id).revision) }
        settings.pauseTyped(accountId)
        transaction { em.persist(AccountAutomationLeaseEntity(accountId, "in-flight", Instant.now().plusSeconds(60), now)) }
        assertFailsWith<ApiException> { presets.apply(accountId, preset.id, presets.get(accountId, preset.id).revision) }
        assertEquals(entry, settings.getTyped(accountId).entries.single())
    }

    @Test
    fun `삭제된 전투 프리셋 참조가 있으면 설정과 열린 작업과 outbox 변경을 모두 되돌린다`() {
        val accountId = account()
        val code = map("battle_map")
        val partyId = transaction {
            val party = PartyPresetEntity(account = em.getReference(HofAccountEntity::class.java, accountId), name = "삭제 대상", createdAt = now, updatedAt = now)
            em.persist(party)
            party.id
        }
        val entry = settings.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.BATTLE_MAP)).entries.single()
        settings.updateBattleMapGroup(accountId, entry.id, UpdateBattleMapGroupRequest(entry.settingsRevision, "원본", true,
            listOf(BattleMapSettingRequest("battle_map", code, 5, PresetSelectionMode.EXPLICIT, partyId, 0))))
        val preset = presets.save(accountId, SaveAutomationPresetRequest("과거 설정"))
        jdbc.update("delete from party_presets where id = ?", partyId)
        transaction {
            em.persist(AutomationWorkSessionEntity(
                account = em.getReference(HofAccountEntity::class.java, accountId),
                entry = em.getReference(AutomationEntryEntity::class.java, entry.id),
                workType = AutomationWorkType.BATTLE_MAP,
                targetKey = "battle_map/$code",
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = "preserved-config",
                targetCount = 5,
                confirmedCount = 2,
                nextCheckAt = now.plusSeconds(600),
                holdMessage = "전투 대기 중",
                createdAt = now,
                updatedAt = now,
            ))
        }
        val before = settings.getTyped(accountId)
        val waiting = work.findWaiting(accountId)
        assertEquals(1, waiting.size)
        val wakes = jdbc.queryForObject("select count(*) from automation_outbox where account_id = ?", Int::class.java, accountId)
        assertFailsWith<ApiException> { presets.apply(accountId, preset.id, presets.get(accountId, preset.id).revision) }
        assertEquals(before, settings.getTyped(accountId))
        assertEquals(waiting, work.findWaiting(accountId))
        assertEquals(wakes, jdbc.queryForObject("select count(*) from automation_outbox where account_id = ?", Int::class.java, accountId))
    }

    @Test
    fun `다른 묶음 배치와 삭제한 항목을 복원하며 일일 진행량은 현재 값을 유지한다`() {
        val accountId = account()
        val code = map("battle_map")
        val first = settings.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.BATTLE_MAP)).entries.single()
        val second = settings.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.BATTLE_MAP)).entries.last()
        settings.updateBattleMapGroup(accountId, first.id, UpdateBattleMapGroupRequest(first.settingsRevision, "첫 묶음", true,
            listOf(BattleMapSettingRequest("battle_map", code, 10, PresetSelectionMode.PRIMARY, null, 0))))
        val preset = presets.save(accountId, SaveAutomationPresetRequest("배치"))
        transaction {
            em.persist(BattleAutomationDailyProgressEntity(account = em.getReference(HofAccountEntity::class.java, accountId),
                progressDate = Instant.now().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate(),
                categoryId = "battle_map", mapCode = code, source = "battle_map", successfulRuns = 4, updatedAt = now))
        }
        val entries = settings.getTyped(accountId).entries
        settings.moveMapBetweenGroups(accountId, second.id, MoveMapBetweenGroupsRequest(first.id,
            entries.first().settingsRevision, entries.last().settingsRevision, "battle_map", code, 0))
        val restored = presets.apply(accountId, preset.id, presets.get(accountId, preset.id).revision)
        assertEquals(listOf(code), restored.entries.first().battleMaps.map { it.mapCode })
        assertEquals(emptyList(), restored.entries.last().battleMaps)
        assertEquals(4, restored.entries.first().battleMapProgress.single().successfulRuns)
        settings.deleteEntry(accountId, second.id, restored.entries.last().settingsRevision)
        assertEquals(2, presets.apply(accountId, preset.id, presets.get(accountId, preset.id).revision).entries.size)
    }

    private fun account(): Long = transaction {
        val account = HofAccountEntity(loginId = "preset-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = now)
        em.persist(account)
        account.id.also { accountIds += it }
    }

    private fun map(category: String): String = transaction {
        val code = "preset-${UUID.randomUUID()}"
        em.persist(BattleMapEntity(categoryId = category, mapCode = code, name = code, normalizedName = code, createdAt = now, updatedAt = now))
        mapCodes += code
        code
    }

    private fun <T : Any> transaction(block: () -> T): T = TransactionTemplate(transactions).execute { block() }
}
