package app.spammy.hof.automation.service

import app.spammy.hof.battle.service.BattleDetailArchiveCollector
import app.spammy.hof.battle.service.BattleLogService
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import java.io.IOException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class AutomationBattleDetailArchiveIntegrationTest : AutomationRecoveryFixture() {
    @Autowired private lateinit var collector: BattleDetailArchiveCollector
    @Autowired private lateinit var logs: BattleLogService

    @Autowired private lateinit var battles: app.spammy.hof.battle.service.BattleRunService

    @org.junit.jupiter.api.Test
    fun `3회 전투의 두 패배 상세를 각각 수집하고 승리 회차와 섞이지 않게 조회한다`() {
        org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult {
            val account = entityManager.find(app.spammy.hof.account.entity.HofAccountEntity::class.java, accountId)
            val map = app.spammy.hof.battle.entity.BattleMapEntity(categoryId = "battle_map", mapCode = "detail-three",
                name = "상세 귀속 테스트", normalizedName = "상세 귀속 테스트", createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(map)
            entityManager.persist(app.spammy.hof.battle.entity.AccountBattleMapStateEntity(account = account,
                battleMap = map, supportsThreeBattles = true, rawHref = "index.php?common=detail-three", lastSeenAt = clock.now()))
        }
        val observed = javaClass.getResource("/fixtures/battle/detail-observed-sanitized.html")!!.readText()
        val urls = (1..3).map { "https://hof.zerosic.com/index.php?log=${123460 + it}" }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val body = if (request.method == HofHttpMethod.POST) {
                "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + urls.mapIndexed { index, url ->
                    val title = if (index == 1) "테스트은(는) 승리했다!" else "적군은(는) 승리했다!"
                    "<h2>Show Detail(18 turns.)</h2><a href='$url'>로그 주소 복사</a><h1>$title</h1>"
                }.joinToString("")
            } else {
                assertTrue(invocation.getArgument<Map<String, String>>(2).isEmpty())
                assertTrue(request.url in listOf(urls[0], urls[2]))
                observed.replace("Knife Desperado", if (request.url == urls[0]) "첫 회차 스킬" else "셋째 회차 스킬")
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val result = battles.runBattle(accountId, app.spammy.hof.battle.dto.RunBattleRequest(
            categoryId = "battle_map", mapCode = "detail-three", characterIds = characters, battleCount = 3))
        assertEquals(listOf("DEFEAT", "VICTORY", "DEFEAT"), result.rounds.map { it.outcome })
        collector.collectDue()
        Mockito.reset(gateway) // 원본 만료 이후의 조회 경계.
        val recorded = logs.findRecent(accountId).sortedBy { it.id }
        assertEquals(listOf("READY", "NONE", "READY"), recorded.map { it.detailArchiveStatus })
        val first = logs.findArchive(accountId, recorded[0].id).html!!
        val third = logs.findArchive(accountId, recorded[2].id).html!!
        assertTrue(first.contains("첫 회차 스킬"))
        assertFalse(first.contains("셋째 회차 스킬"))
        assertTrue(third.contains("셋째 회차 스킬"))
        assertFalse(third.contains("첫 회차 스킬"))
        assertNull(logs.findArchive(accountId, recorded[1].id).html)
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST })
        assertEquals(listOf(urls[0], urls[2]), requests.filter { it.method == HofHttpMethod.GET }.map { it.url })
        Mockito.verifyNoInteractions(gateway)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUCCESS", "IO", "DEFERRED"])
    fun `상세 보관 성공 실패 유예 모두 후속 wake를 소비해 다음 전투 판단을 이어간다`(archiveResult: String) {
        failedPattern = 0
        var posts = 0
        val detailUrl = "https://hof.zerosic.com/index.php?log=123456"
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val body = when {
                request.url == detailUrl -> {
                    assertEquals(HofHttpMethod.GET, request.method)
                    assertTrue(invocation.getArgument<Map<String, String>>(2).isEmpty())
                    if (archiveResult == "IO") throw IOException("detail timeout")
                    if (archiveResult == "DEFERRED") throw HofAutomationDeferredException(clock.now().plusSeconds(60), 1)
                    "<div id='contents'><table><tr><td class='ttd1'>1턴 공격</td><td class='ttd2'><span class='dmg'>156 데미지</span></td></tr></table></div>"
                }
                request.url.contains("?char=") -> "<div>Funds : $ 1 Time : 100/100</div>" +
                    app.spammy.hof.character.service.currentPatternForm() +
                    app.spammy.hof.character.service.savedPatternLoadForm(1)
                request.method == HofHttpMethod.GET -> mapPage
                else -> {
                    posts++
                    val title = if (posts == 1) "적군은(는) 승리했다!" else "테스트은(는) 승리했다!"
                    "<div id='menu2'>Funds : $ 1 Time : 100/100</div><a href='$detailUrl'>Show Detail(1 turns.)</a><h1>$title</h1>"
                }
            }
            HofHttpResponse(200, request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        wakeups.wake(accountId, "DETAIL_ARCHIVE_TEST")
        publisher.publishBatch()
        assertEquals(1, posts)
        assertEquals(0, runningWorkCount())
        val defeat = logs.findRecent(accountId).single()
        assertEquals("DEFEAT", defeat.outcome)
        assertEquals("PENDING", defeat.detailArchiveStatus)
        collector.collectDue()
        assertEquals(if (archiveResult == "SUCCESS") "READY" else "PENDING", logs.findArchive(accountId, defeat.id).archiveStatus)
        assertEquals(1, posts, "상세 수집은 전투를 다시 제출하지 않는다")

        consumeNextWake()
        assertEquals(2, posts, "후속 wake의 실제 다음 판단에서 새 전투를 실행한다")
        assertEquals(0, runningWorkCount())
        assertEquals(listOf("VICTORY", "DEFEAT"), logs.findRecent(accountId).map { it.outcome })
        if (archiveResult == "SUCCESS") assertTrue(logs.findArchivedHtml(accountId, defeat.id).contains("156 데미지"))
    }
}
