package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleLogQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofBattleLoot
import app.spammy.hof.external.model.HofBattleOutcome
import app.spammy.hof.external.model.HofBattleResult
import app.spammy.hof.external.model.HofBattleSide
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import app.spammy.hof.battle.dto.AdventureMapStatsPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    BattleMapQueryRepository::class,
    BattleLogQueryRepository::class,
    BattleLogService::class,
    BattleDetailArchiveCollector::class,
    BattleDetailArchiveStore::class,
    BattleLogServiceTest.FixedTimeConfig::class,
)
class BattleLogServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var mapRepository: BattleMapRepository

    @Autowired
    private lateinit var queryRepository: BattleLogQueryRepository

    @Autowired
    private lateinit var service: BattleLogService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var collector: BattleDetailArchiveCollector

    @Autowired
    private lateinit var archiveStore: BattleDetailArchiveStore

    @Autowired
    private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @org.junit.jupiter.api.AfterEach
    fun removeArchiveTestAccounts() { jdbc.update("delete from hof_accounts where login_id like 'detail-%'") }

    @Autowired
    private lateinit var archiveTime: java.util.concurrent.atomic.AtomicReference<Instant>

    @org.junit.jupiter.api.BeforeEach
    fun resetArchiveAdapter() {
        archiveTime.set(NOW)
        org.mockito.Mockito.reset(detailGateway)
    }

    private val observedDetailHtml get() = javaClass.getResource("/fixtures/battle/detail-observed-sanitized.html")!!.readText()

    private fun detailRequest(url: String) = app.spammy.hof.external.model.HofRequest(
        app.spammy.hof.external.model.HofHttpMethod.GET, url, origin = app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION, followRedirects = false, timeoutSeconds = 20)

    @Autowired
    private lateinit var detailGateway: app.spammy.hof.external.client.HofGateway

    @Test
    fun recordPersistsParentParticipantsAndLootsInExactOrder() {
        val account = savedAccount("battle-log-record")
        val battleMap = savedMap("battle_map", "test-snow22", "Frosty Mountain")
        val characters = (0 until 5).map { index ->
            characterRepository.save(
                CharacterEntity(
                    account = account,
                    hofCharacterId = "hof-$index",
                    name = "참가자-$index",
                    job = "검사",
                    updatedAt = NOW,
                ),
            )
        }

        val response = service.record(
            account = account,
            request = request(battleMap.categoryId, battleMap.mapCode, characters),
            characters = characters,
            result = result(
                outcome = HofBattleOutcome.VICTORY,
                funds = 3_660,
                experience = 10_590,
                loots = listOf(
                    HofBattleLoot(name = "Steel Ingot", quantity = 2, rawText = "Steel Ingot x 2"),
                    HofBattleLoot(name = "Bone", quantity = 1, rawText = "Bone"),
                ),
            ),
        )

        inTransaction {
            val stored = queryRepository.findRecent(account.id, 20).single()
            assertEquals(battleMap.id, stored.log.battleMap?.id)
            assertEquals("battle_map", stored.log.categoryIdSnapshot)
            assertEquals("test-snow22", stored.log.mapCodeSnapshot)
            assertEquals("Frosty Mountain", stored.log.mapNameSnapshot)
            assertEquals(listOf(0, 1, 2, 3, 4), stored.participants.map { it.slotIndex })
            assertEquals(characters.map { it.id }, stored.participants.map { it.character?.id })
            assertEquals(characters.map { it.hofCharacterId }, stored.participants.map { it.hofCharacterIdSnapshot })
            assertEquals(characters.map { it.name }, stored.participants.map { it.characterNameSnapshot })
            assertEquals(listOf(0, 1), stored.loots.map { it.displayOrder })
            assertEquals(listOf("Steel Ingot", "Bone"), stored.loots.map { it.name })
            assertEquals(listOf(2, 1), stored.loots.map { it.quantity })
            assertEquals(listOf("Steel Ingot x 2", "Bone"), stored.loots.map { it.rawText })
            assertEquals(5L, queryRepository.countParticipants(stored.log.id))
        }
        assertEquals("Frosty Mountain", response.mapName)
        assertEquals(characters.map { it.hofCharacterId }, response.characterIds)
        assertEquals(characters.map { it.name }, response.characterNames)
        assertEquals("VICTORY", response.outcome)
        assertEquals(3_660, response.funds)
        assertEquals(10_590, response.experience)
        assertEquals(listOf("Steel Ingot x 2", "Bone"), response.loots.map { it.name })
        assertEquals(NOW.toString(), response.createdAt)

        val stats = service.summarize(account.id)
        assertEquals(3_660L, stats.dailyFunds)
        assertEquals(3_660L, stats.weeklyFunds)
        assertEquals(3_660L, stats.monthlyFunds)
        assertEquals(1L, stats.dailyBattles)
        assertEquals(1L, stats.weeklyBattles)
        assertEquals(1L, stats.monthlyBattles)
        assertEquals(emptyList(), stats.adventureMapOutcomes)
    }

    @Test
    fun `지정 한국 날짜의 시작은 포함하고 다음 날 시작과 다른 계정은 제외한다`() {
        val account = savedAccount("detail-date-stats")
        val other = savedAccount("detail-date-stats-other")
        val date = LocalDate.of(2026, 7, 1)
        val start = date.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        fun record(at: Instant, outcome: HofBattleOutcome, owner: HofAccountEntity = account) {
            archiveTime.set(at)
            service.record(owner, request("adventure_map", "date-map", emptyList()), emptyList(), result(outcome))
        }
        record(start.minusSeconds(1), HofBattleOutcome.DEFEAT)
        record(start, HofBattleOutcome.VICTORY)
        record(start.plusSeconds(86_399), HofBattleOutcome.DRAW)
        record(start.plusSeconds(86_400), HofBattleOutcome.DEFEAT)
        record(start.plusSeconds(1), HofBattleOutcome.DEFEAT, other)
        archiveTime.set(start.plusSeconds(172_800))

        val stats = service.summarize(account.id, date = date).adventureMapOutcomes.single()
        assertEquals(2L, stats.totalBattles)
        assertEquals(0L, stats.defeats)
        assertEquals(1L, stats.draws)
        assertEquals(1L, service.summarize(account.id, date = date.plusDays(1)).adventureMapOutcomes.single().defeats)
        assertTrue(service.summarize(account.id, date = date.minusDays(2)).adventureMapOutcomes.isEmpty())
        assertEquals(ErrorCode.INVALID_REQUEST, assertFailsWith<ApiException> {
            service.summarize(account.id, date = date.plusDays(3))
        }.errorCode)
        assertFailsWith<ApiException> { service.summarize(account.id, AdventureMapStatsPeriod.WEEK, date) }
    }

    @Test
    fun `맵 이름 변경은 합산하고 같은 코드의 전투맵은 구분하며 승리만 있는 맵은 숨긴다`() {
        val account = savedAccount("adventure-total-count")
        val map = savedMap("adventure_map", "total-map", "변경 전 이름")
        fun record(mapCode: String, outcome: HofBattleOutcome, categoryId: String = "adventure_map") {
            service.record(
                account = account,
                request = RunBattleRequest(categoryId = categoryId, mapCode = mapCode, characterIds = emptyList()),
                characters = emptyList(),
                result = result(outcome),
            )
        }
        record("total-map", HofBattleOutcome.VICTORY)
        map.name = "변경 후 이름"
        mapRepository.save(map)
        record("total-map", HofBattleOutcome.DEFEAT)
        record("total-map", HofBattleOutcome.DRAW)
        record("victory-map", HofBattleOutcome.VICTORY)
        record("total-map", HofBattleOutcome.DEFEAT, "battle_map")
        record("total-map", HofBattleOutcome.VICTORY, "battle_map")
        record("victory-map", HofBattleOutcome.VICTORY, "battle_map")

        val maps = service.summarize(account.id).adventureMapOutcomes
        assertEquals(2, maps.size)
        val stats = maps.single { it.categoryId == "adventure_map" }
        assertEquals("total-map", stats.mapCode)
        assertEquals(3L, stats.totalBattles)
        assertEquals(1L, stats.defeats)
        assertEquals(1L, stats.draws)
        val battleStats = maps.single { it.categoryId == "battle_map" }
        assertEquals("total-map", battleStats.mapCode)
        assertEquals(2L, battleStats.totalBattles)
        assertEquals(1L, battleStats.defeats)
        assertEquals(0L, battleStats.draws)
    }

    @Test
    fun `패배 상세 페이지를 보관하면 원본 링크가 만료돼도 별도 페이지의 턴별 내용을 읽는다`() {
        val account = savedAccount("detail-html-expiry")
        val url = "https://hof.zerosic.com/index.php?alog=123456"
        val log = service.record(account, request("adventure_map", "snow22", emptyList()), emptyList(),
            result(HofBattleOutcome.DEFEAT).copy(rawLogUrl = url),
            "<html><body><a href='$url'>Show Detail</a><h1>적군이 승리했다!</h1></body></html>")
        org.mockito.Mockito.`when`(detailGateway.execute(account.id,
            detailRequest(url), emptyMap())).thenReturn(
            app.spammy.hof.external.model.HofHttpResponse(200, url,
                observedDetailHtml.replace("<body>", "<body><div id='menu2'>계정 비밀</div><script>secret()</script>"), emptyMap()))
        collector.collectDue()
        org.mockito.Mockito.reset(detailGateway) // 원본 접근 불가: 이후 조회는 저장된 HTML만 사용한다.
        val html = service.findArchivedHtml(account.id, log.id)
        val original = org.jsoup.Jsoup.parse(observedDetailHtml)
        val saved = org.jsoup.Jsoup.parse(html)
        assertEquals(original.select("#contents table").size, saved.select("table").size)
        // 전체 셀의 텍스트와 DOM 순서를 비교하여 적군/아군·다중 턴의 귀속까지 확인한다.
        assertEquals(original.select("#contents td").map { it.text() }, saved.select("td").map { it.text() })
        assertTrue(saved.text().contains("Knife Desperado"))
        assertTrue(saved.text().contains("의 행동력 변경"))
        assertTrue(saved.text().contains("회복되었다"))
        assertTrue(saved.text().contains("소멸하였다"))
        assertTrue(original.select(".dmg").size > 10)
        assertTrue(original.select("td.ttd1").size > 10)
        assertTrue(original.select("td.ttd2").size > 10)
        assertFalse(html.contains("계정 비밀"))
        assertFalse(html.contains("secret()"))
        org.mockito.Mockito.verifyNoInteractions(detailGateway)
    }

    @Test
    fun `상세 수집 실패는 패배 기록을 보존하고 재시도 예산 뒤 실패로 표시한다`() {
        val account = savedAccount("detail-retry-budget")
        val url = "https://hof.zerosic.com/index.php?log=123457"
        val log = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(),
            result(HofBattleOutcome.DEFEAT).copy(rawLogUrl = url), "<html><body>전투 결과 요약</body></html>")
        org.mockito.Mockito.`when`(detailGateway.execute(account.id, detailRequest(url), emptyMap()))
            .thenAnswer { throw java.io.IOException("timeout") }
        repeat(3) {
            collector.collectDue()
            archiveTime.set(archiveTime.get().plusSeconds(30))
        }
        val response = service.findArchive(account.id, log.id)
        assertEquals("FAILED", response.archiveStatus)
        assertEquals("RESPONSE", response.kind)
        assertTrue(response.html!!.contains("전투 결과 요약"))
        assertEquals("DEFEAT", service.findRecent(account.id).single().outcome)
        collector.collectDue()
        org.mockito.Mockito.verify(detailGateway, org.mockito.Mockito.times(3)).execute(account.id, detailRequest(url), emptyMap())
    }

    @Test
    fun `상세 수집 도중 재시작해도 저장된 대기를 복구하고 같은 패배 기록을 완성한다`() {
        val account = savedAccount("detail-restart")
        val url = "https://hof.zerosic.com/index.php?alog=123458"
        val log = service.record(account, request("adventure_map", "snow22", emptyList()), emptyList(),
            result(HofBattleOutcome.DEFEAT).copy(rawLogUrl = url), "<html><body>응답 요약</body></html>")
        org.mockito.Mockito.`when`(detailGateway.execute(account.id, detailRequest(url), emptyMap()))
            .thenAnswer { throw AssertionError("process stopped after durable claim") }
        assertFailsWith<AssertionError> { collector.collectDue() }
        assertEquals("PENDING", service.findArchive(account.id, log.id).archiveStatus)
        archiveTime.set(NOW.plusSeconds(121))
        org.mockito.Mockito.reset(detailGateway)
        org.mockito.Mockito.`when`(detailGateway.execute(account.id, detailRequest(url), emptyMap()))
            .thenAnswer {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                app.spammy.hof.external.model.HofHttpResponse(200, url, detailedFixture, emptyMap())
            }
        BattleDetailArchiveCollector(archiveStore, detailGateway).collectDue()
        assertEquals("READY", service.findArchive(account.id, log.id).archiveStatus)
        assertEquals(listOf(log.id), service.findRecent(account.id).map { it.id })
        assertTrue(service.findArchivedHtml(account.id, log.id).contains("1턴 아군 공격"))
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["LOGIN", "CAPTCHA", "SUMMARY", "EMPTY", "REDIRECT", "EXTERNAL"])
    fun `로그인 캡차 요약 빈문서 redirect 외부문서를 상세 보관 성공으로 표시하지 않는다`(page: String) {
        val account = savedAccount("detail-invalid-$page")
        val url = "https://hof.zerosic.com/index.php?log=123459"
        val log = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(),
            result(HofBattleOutcome.DEFEAT).copy(rawLogUrl = url), "<html><body>원래 전투 응답</body></html>")
        val body = when (page) {
            "LOGIN" -> "<div id='contents'><form><input name='id'><input name='pass'></form></div>"
            "CAPTCHA" -> "<div id='contents'>자경단에서 통행증을 발급받아주세요.</div>"
            "SUMMARY" -> "<div id='contents'><h1>적군이 승리했다!</h1><p>총 데미지: 156</p></div>"
            "EMPTY" -> ""
            else -> detailedFixture
        }
        val finalUrl = if (page == "EXTERNAL") "https://example.test/index.php?log=123459" else url
        org.mockito.Mockito.`when`(detailGateway.execute(account.id, detailRequest(url), emptyMap()))
            .thenReturn(app.spammy.hof.external.model.HofHttpResponse(if (page == "REDIRECT") 302 else 200, finalUrl, body, emptyMap()))
        collector.collectDue()
        val archive = service.findArchive(account.id, log.id)
        assertEquals("PENDING", archive.archiveStatus)
        assertEquals("RESPONSE", archive.kind)
        assertTrue(archive.html!!.contains("원래 전투 응답"))
    }

    @Test
    fun `미전송 유예는 HTTP 시도 예산을 쓰지 않으며 원래 재개 시각 뒤 상세를 보관한다`() {
        val account = savedAccount("detail-deferred")
        val url = "https://hof.zerosic.com/index.php?log=123460"
        val log = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(),
            result(HofBattleOutcome.DEFEAT).copy(rawLogUrl = url), "<p>요약</p>")
        repeat(4) {
            org.mockito.Mockito.reset(detailGateway)
            org.mockito.Mockito.`when`(detailGateway.execute(account.id, detailRequest(url), emptyMap()))
                .thenAnswer { throw app.spammy.hof.external.client.HofAutomationDeferredException(archiveTime.get().plusSeconds(15), 1) }
            collector.collectDue()
            archiveTime.set(archiveTime.get().plusSeconds(15))
            assertEquals("PENDING", service.findArchive(account.id, log.id).archiveStatus)
        }
        org.mockito.Mockito.reset(detailGateway)
        org.mockito.Mockito.`when`(detailGateway.execute(account.id, detailRequest(url), emptyMap()))
            .thenReturn(app.spammy.hof.external.model.HofHttpResponse(200, url, detailedFixture, emptyMap()))
        collector.collectDue()
        assertEquals("READY", service.findArchive(account.id, log.id).archiveStatus)
    }

    private val detailedFixture = "<div id='contents'><table><tr><td class='ttd1'>1턴 아군 공격</td><td class='ttd2'><span class='dmg'>상대에게 156 데미지</span></td></tr></table></div>"

    @Test
    fun recordKeepsRequestSnapshotsWhenCatalogMapIsUnavailable() {
        val account = savedAccount("battle-log-missing-map")
        val character = characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "missing-map-character",
                name = "기록 보존 참가자",
                job = "검사",
                updatedAt = NOW,
            ),
        )

        val response = service.record(
            account = account,
            request = request("unknown_category", "unknown-code", listOf(character)),
            characters = listOf(character),
            result = result(HofBattleOutcome.UNKNOWN),
        )

        assertEquals("unknown-code", response.mapName)

        inTransaction {
            val stored = queryRepository.findRecent(account.id, 1).single().log
            assertNull(stored.battleMap)
            assertEquals("unknown_category", stored.categoryIdSnapshot)
            assertEquals("unknown-code", stored.mapCodeSnapshot)
            assertEquals("unknown-code", stored.mapNameSnapshot)
        }
    }

    @Test
    fun `패배 HTML은 재조회 가능하며 다른 계정과 승리에는 보관본을 제공하지 않는다`() {
        val account = savedAccount("html-archive")
        val other = savedAccount("html-other")
        val source = """<html><body><div style="display:none" onclick="evil()">1턴 공격 상세</div><table><tr><td>패배</td></tr></table><script>secret()</script><form><input value="password"></form><img src="https://example.com/tracker"></body></html>"""
        val defeat = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(), result(HofBattleOutcome.DEFEAT), source)
        assertTrue(defeat.hasArchivedHtml)
        val html = service.findArchivedHtml(account.id, defeat.id)
        assertTrue(html.contains("1턴 공격 상세"))
        assertTrue(html.contains("<table>"))
        assertFalse(html.contains("display:none"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("secret()"))
        assertFalse(html.contains("password"))
        assertFalse(html.contains("tracker"))
        val forbidden = assertFailsWith<ApiException> { service.findArchivedHtml(other.id, defeat.id) }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, forbidden.errorCode)
        val victory = service.record(account, request("battle_map", "snow22", emptyList()), emptyList(), result(HofBattleOutcome.VICTORY), source)
        assertFalse(victory.hasArchivedHtml)
        assertFailsWith<ApiException> { service.findArchivedHtml(account.id, victory.id) }
        assertTrue(service.findRecent(account.id).single { it.id == defeat.id }.hasArchivedHtml)
    }

    @Test
    fun fundPeriodsStartAtKoreaMidnightMondayAndFirstDayOfMonth() {
        val periods = battleFundPeriodStarts(Instant.parse("2026-08-05T03:00:00Z"))

        assertEquals(Instant.parse("2026-08-04T15:00:00Z"), periods.day)
        assertEquals(Instant.parse("2026-08-02T15:00:00Z"), periods.week)
        assertEquals(Instant.parse("2026-07-31T15:00:00Z"), periods.month)
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedMap(
        categoryId: String,
        mapCode: String,
        name: String,
    ): BattleMapEntity =
        mapRepository.save(
            BattleMapEntity(
                categoryId = categoryId,
                mapCode = mapCode,
                name = name,
                normalizedName = name.lowercase(),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun request(
        categoryId: String,
        mapCode: String,
        characters: List<CharacterEntity>,
    ): RunBattleRequest =
        RunBattleRequest(
            categoryId = categoryId,
            mapCode = mapCode,
            characterIds = characters.map { it.hofCharacterId },
        )

    private fun result(
        outcome: HofBattleOutcome,
        funds: Int? = null,
        experience: Int? = null,
        loots: List<HofBattleLoot> = emptyList(),
    ): HofBattleResult =
        HofBattleResult(
            outcome = outcome,
            title = "전투 결과",
            turns = 5,
            funds = funds,
            experience = experience,
            loots = loots,
            quest = null,
            enemySide = HofBattleSide.EMPTY,
            allySide = HofBattleSide.EMPTY,
        )

    private fun inTransaction(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult { block() }
    }

    @TestConfiguration
    class FixedTimeConfig {
        @Bean
        fun archiveTime() = java.util.concurrent.atomic.AtomicReference(NOW)

        @Bean
        fun timeProvider(archiveTime: java.util.concurrent.atomic.AtomicReference<Instant>): TimeProvider = TimeProvider { archiveTime.get() }

        @Bean
        fun detailGateway(): app.spammy.hof.external.client.HofGateway = org.mockito.Mockito.mock(app.spammy.hof.external.client.HofGateway::class.java)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
