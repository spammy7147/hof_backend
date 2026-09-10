package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.raid.RaidCooldownSource
import app.spammy.hof.automation.raid.RaidCycleStore
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationRaidBattleContinuityTest : AutomationRaidBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationRaidBattleContinuityTest : AutomationRaidBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationRaidBattleContinuityTest : AutomationRaidBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationRaidBattleContinuityTest : AutomationRecoveryFixture() {
    @Autowired private lateinit var raidStore: RaidCycleStore

    @Test
    fun `레이드 전투의 직접 결과를 보존하고 개인 쿨다운 동안 독립 자택과 다음 판단을 이어간다`() {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        var homeAccepted = false
        var battles = 0
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        val raidPage = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("현재사용자", "테스트")
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("현재 상태 : 모집 중", "현재 상태 : 전투 중")
        val mapFixture = requireNotNull(javaClass.getResource("/fixtures/raid/raid-complete-absent.html")).readText()
        fun raidMaps() = mapFixture.replace("<p>진행 중인 전투가 없습니다.</p>", """<div id='mapgroup1'><div>
            ${if (battles > 0) "<span>다음 전투까지 99초 남음</span>" else ""}
            <a href='index.php?raid_common=RaidGoblin'>고블린 전투 마차</a></div></div>""")
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            entityManager.persist(RaidAutomationCycleEntity(account = entry.account, entry = entry, raidId = "RaidGoblin",
                raidName = "고블린 전투 마차", status = RaidAutomationCycleStatus.IN_BATTLE,
                lastObservedStatus = "전투 중", startedAt = clock.now(), updatedAt = clock.now()))
            val preset = PartyPresetEntity(account = entry.account, name = "레이드 전투 파티", isPrimary = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(preset)
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.forEachIndexed { index, character ->
                    val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
                    entityManager.persist(pattern)
                    entityManager.persist(PartyPresetMemberEntity(preset, index, character, pattern))
                }
            val homeEntry = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST,
                priority = 1, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = home.id,
                questName = home.name, enabled = true, sourceOrder = 0))
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val body = when {
                request.url.contains("?char=") -> "<div>Funds : $ 1 Time : 100/100</div>" +
                    app.spammy.hof.character.service.currentPatternForm() + app.spammy.hof.character.service.savedPatternLoadForm(1)
                request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals(HofHttpMethod.GET, request.method)
                        assertEquals("A", request.formFields["no"])
                        homeAccepted = true
                    }
                    homePage()
                }
                request.url.contains("menu=raidpub") -> {
                    assertEquals(HofHttpMethod.GET, request.method)
                    raidPage
                }
                request.url.endsWith("?raid_hunt") -> raidMaps()
                else -> {
                    assertEquals(HofHttpMethod.POST, request.method)
                    assertEquals("https://hof.zerosic.com/index.php?raid_common=RaidGoblin", request.url)
                    battles++
                    """<div id="menu2">Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>테스트은(는) 승리했다!</h1>
                        <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                        <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        wakeups.wake(accountId, "RAID_BATTLE_DIRECT_RESULT")
        publisher.publishBatch()

        assertEquals(1, battles, journal.page(accountId, AutomationHistoryQuery()).cycles.toString())
        assertFalse(homeAccepted)
        val identity = assertIs<String>(runs().single()["execution_identity"])
        val payload = jacksonObjectMapper().readTree(assertNotNull(jdbc.queryForObject(
            "select payload_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)))["payload"]
        assertEquals("RAID_AUTOMATION", payload["source"].asString())
        assertEquals("RaidGoblin", payload["sourceTargetKey"].asString())
        assertEquals("raid", payload["categoryId"].asString())
        fun assertResultPreserved() {
            assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertNotNull(record)
                assertEquals(AutomationActionKind.RAID_BATTLE, record.selection.actionKind)
                assertEquals(ActionConvergenceResult.APPLIED, record.result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED") else emptyList(),
                jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        }
        assertResultPreserved()
        val result = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.RAID }
        assertEquals("BATTLE_MAP", result.actionKind)
        assertEquals("TYPED_ACTION_COMPLETED", result.reasonCode)
        assertEquals("RaidGoblin", result.targetKey)
        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted, journal.page(accountId, AutomationHistoryQuery()).cycles.toString())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(1, battles)
        assertResultPreserved()
        val histories = journal.page(accountId, AutomationHistoryQuery()).cycles
        val cooldowns = histories.flatMap { it.events }.filter {
            it.type == AutomationType.RAID && it.cooldownSource == RaidCooldownSource.HOF_DIRECT
        }
        assertTrue(cooldowns.isNotEmpty(), histories.toString())
        cooldowns.forEach {
            assertEquals(RaidCooldownSource.HOF_DIRECT, it.cooldownSource)
            assertEquals(it.occurredAt.plusSeconds(99), it.nextRunAt)
        }
        assertEquals(result, histories.flatMap { it.events }.single { it.id == result.id })
        val cycle = assertNotNull(raidStore.load(accountId).openCycle)
        assertEquals(RaidAutomationCycleStatus.IN_BATTLE, cycle.status)
        assertNull(cycle.battleRecovery)
        val gate = assertNotNull(cycle.battleSafetyGate)
        assertEquals(RaidCooldownSource.HOF_DIRECT, gate.source)
        assertEquals(cooldowns.first().nextRunAt, gate.notBefore)
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }
}
