package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationQuestBattleContinuityTest : AutomationQuestBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationQuestBattleContinuityTest : AutomationQuestBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationQuestBattleContinuityTest : AutomationQuestBattleContinuityTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationQuestBattleContinuityTest : AutomationRecoveryFixture() {
    @Test
    fun `퀘스트 전투의 직접 승리를 한 번 기록하고 독립 자택과 다음 판단을 이어간다`() {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val questUrl = "https://hof.zerosic.com/index.php?menu=quest"
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        val questFixture = requireNotNull(javaClass.getResource("/fixtures/quest/quest-complete-empty.html")).readText()
            .replaceFirst("</table>", """<tr><td class="td7s">[0571] 저택 서관 열쇠 수집</td>
                <td>미션 : 몬스터 처치( Killer Maid ) - [ 12 / 30 ]</td><td>-</td><td>-</td>
                <td class="td8s">-</td></tr></table>""")
        var battleCount = 0
        var homeAccepted = false
        fun questPage() = questFixture.replace("[ 12 / 30 ]", if (battleCount == 0) "[ 12 / 30 ]" else "[ 13 / 30 ]")
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        fun maps() = """<html><body><div id='menu2'>Funds : $ 1 Time : 100/100</div>
            <div id='contents'><div>공유 지역 (2)</div><div id='mapgroup1'>
            <p><a href='index.php?common=0003'>도적소탕${if (battleCount > 0) " (1분) 남음" else ""}</a> 2 가능</p></div></div>
            <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
            <img src='image/zerohof.gif'></div></body></html>"""
        val observation = QuestPageParser().parseObservation(questPage(), questUrl)
        assertTrue(observation.complete)
        val quest = observation.quests.single { it.displayCode == "0571" }
        val mission = quest.missions.single()
        assertEquals(12, mission.progress?.current)
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.QUEST
            entry.singletonTypeMarker = AutomationType.QUEST
            val selection = QuestAutomationSelectionEntity(entry = entry, questKey = quest.questKey,
                displayCode = quest.displayCode, questName = quest.name, enabled = true, sourceOrder = 0)
            entityManager.persist(selection)
            entityManager.persist(QuestAutomationMapEntity(questSelection = selection, missionKey = mission.key,
                categoryId = "battle_map", mapCode = "0003", presetMode = PresetSelectionMode.PRIMARY,
                executionOrder = 0, manuallyOverridden = true))
            val preset = PartyPresetEntity(account = entry.account, name = "퀘스트 전투 파티", isPrimary = true,
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
                request.url.contains("menu=housing") || request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals(HofHttpMethod.GET, request.method)
                        assertEquals("A", request.formFields["no"])
                        homeAccepted = true
                    }
                    homePage()
                }
                request.url.contains("menu=quest") -> {
                    assertEquals(HofHttpMethod.GET, request.method, "선택하지 않은 퀘스트의 수락·수령을 제출하면 안 된다.")
                    questPage()
                }
                request.method == HofHttpMethod.GET -> maps()
                else -> {
                    assertEquals("https://hof.zerosic.com/index.php?common=0003", request.url)
                    battleCount++
                    """<div id="menu2">Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>테스트은(는) 승리했다!</h1>
                        <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                        <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        wakeups.wake(accountId, "QUEST_BATTLE_DIRECT_RESULT")
        publisher.publishBatch()

        assertEquals(1, battleCount)
        assertFalse(homeAccepted, "첫 판단은 앞선 퀘스트 전투 하나만 제출한다.")
        val identity = assertNotNull(jdbc.queryForObject(
            "select execution_identity from typed_automation_action_runs where account_id = ? and action_kind = 'QUEST_BATTLE'",
            String::class.java, accountId))
        val payload = jacksonObjectMapper().readTree(assertNotNull(jdbc.queryForObject(
            "select payload_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)))["payload"]
        assertEquals("QUEST_BATTLE", payload["kind"].asString())
        assertEquals(quest.questKey, payload["questKey"].asString())
        assertEquals(mission.key, payload["missionKey"].asString())
        assertEquals(1, payload["battleCount"].asInt())
        fun assertResultPreserved() {
            assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertNotNull(record)
                assertEquals(AutomationActionKind.QUEST_BATTLE, record.selection.actionKind)
                assertEquals(ActionConvergenceResult.APPLIED, record.result)
                assertEquals("DIRECT_RESPONSE_APPLIED", record.reasonCode)
            } else assertNull(record)
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW) listOf("APPLIED") else emptyList(),
                jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
            assertEquals(1, jdbc.queryForObject(
                "select successful_runs from quest_map_execution_counters where account_id = ? and quest_code = ? and mission_key = ? and category_id = 'battle_map' and map_code = '0003'",
                Int::class.java, accountId, quest.questKey, mission.key))
            assertEquals(1, jdbc.queryForObject(
                "select count(*) from quest_automation_processed_results where account_id = ? and result_kind = 'BATTLE_VICTORY'",
                Int::class.java, accountId))
        }
        assertResultPreserved()
        val result = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .single { it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.actionKind == "QUEST_BATTLE" }
        assertEquals("battle_map/0003", result.targetKey)
        val initialCycles = journal.page(accountId, AutomationHistoryQuery()).cycles.map { it.id }.toSet()

        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted, "퀘스트 전투 쿨다운은 독립 자택의 실제 제출을 막으면 안 된다. " +
            "requests=${requests.map { it.method to it.url }}, history=${journal.page(accountId, AutomationHistoryQuery()).cycles}")
        assertEquals(1, requests.count { it.method == HofHttpMethod.GET && it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(1, battleCount, "현재 쿨다운과 원래 실행의 직접 결과를 보존해 같은 전투를 중복 제출하지 않는다.")
        assertResultPreserved()
        val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertTrue(cycles.count { it.id !in initialCycles } >= 2)
        assertEquals(result, cycles.flatMap { it.events }.single { it.id == result.id })
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }
}
