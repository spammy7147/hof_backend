package app.spammy.hof.town.fishing.parser

import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FishingPageParserTest {
    private val forms = HofFormParser()
    private val results = HofResultParser()
    private val parser = FishingPageParser()
    private val url = "https://hof.zerosic.com/index.php?menu=fishing"

    @Test
    fun `날짜 갱신과 시작 action을 읽는다`() {
        val html = fixture("reset.html")
        val reset = parser.parse(html, url, forms.parse(html, url))

        assertTrue(reset.notice.orEmpty().contains("날짜가 갱신되었습니다"))
        assertEquals(18, reset.remainingCasts)
        assertEquals(FishingPrimaryAction.START, reset.primaryAction)
        assertEquals(setOf(FishingAction.START), reset.availableActions.map { it.action }.toSet())
    }

    @Test
    fun `낚기와 보조 action을 연결하되 자동 실행하지 않는다`() {
        val html = fixture("waiting.html")
        val waiting = parser.parse(html, url, forms.parse(html, url))

        assertEquals(FishingPrimaryAction.CATCH, waiting.primaryAction)
        assertEquals(FishingOutcome.STARTED, waiting.lastOutcome)
        assertEquals(30, waiting.escapeSeconds)
        assertEquals(setOf(FishingAction.CATCH, FishingAction.STATUS, FishingAction.FILTER), waiting.availableActions.map { it.action }.toSet())
    }

    @Test
    fun `START 직접 응답에 CATCH form이 아직 없어도 시작 적용으로 읽는다`() {
        val html = fixture("waiting-no-catch.html")
        val waiting = parser.parse(html, url, forms.parse(html, url), results.parse(html))

        assertEquals(FishingOutcome.STARTED, waiting.lastOutcome)
    }

    @Test
    fun `물고기 도망은 정상 outcome이다`() {
        val html = fixture("escaped.html")
        val escaped = parser.parse(html, url, forms.parse(html, url), results.parse(html))

        assertEquals(FishingOutcome.ESCAPED, escaped.lastOutcome)
        assertEquals(FishingPrimaryAction.START, escaped.primaryAction)
    }

    @Test
    fun `전투 링크가 있으면 낚시 action을 차단한다`() {
        val html = fixture("monster.html")
        val monster = parser.parse(html, url, forms.parse(html, url))

        assertTrue(monster.blockedByBattle)
        assertEquals("battle_map", assertNotNull(monster.battleTarget).categoryId)
        assertEquals("fishing_12", monster.battleTarget?.mapCode)
        assertEquals(FishingPrimaryAction.NONE, monster.primaryAction)
        assertTrue(monster.availableActions.isEmpty())
    }

    @Test
    fun `빨간 전투 경고만 있고 링크가 없어도 낚시 action을 차단한다`() {
        val html = fixture("monster.html").replace("<a href=\"?menu=hunt&amp;common=fishing_12\">전투</a>", "")
        val monster = parser.parse(html, url, forms.parse(html, url))

        assertTrue(monster.blockedByBattle)
        assertEquals(null, monster.battleTarget)
        assertEquals(FishingPrimaryAction.NONE, monster.primaryAction)
        assertTrue(monster.availableActions.isEmpty())
    }

    @Test
    fun `실서버 경고 색상이 표준 red가 아니어도 낚시터 몬스터 문구를 차단 상태로 인식한다`() {
        val html = """
            <html><body><section id="fishing">
              <font color="#ff4500">낚시터에 나타난 몬스터 때문에 낚시가 불가능합니다! 전투를 끝내면 됩니다.</font>
              <p>(오늘의 남은 낚시 횟수 : 3회)</p>
              <form method="post"><input type="submit" name="do" value="낚시를 시작한다"></form>
            </section></body></html>
        """.trimIndent()

        val snapshot = parser.parse(html, url, forms.parse(html, url))

        assertTrue(snapshot.blockedByBattle)
        assertEquals(FishingPrimaryAction.NONE, snapshot.primaryAction)
        assertTrue(snapshot.availableActions.isEmpty())
    }

    @Test
    fun `실서버 START 응답의 낚시하기 불가능 문구도 방해 전투로 인식한다`() {
        val html = """
            <html><body><section id="fishing">
              <font color="#ff4500">낚시터에 나타난 몬스터 때문에 낚시하기가 불가능합니다!! 전투해 물리칩시다.</font>
              <p>(오늘의 남은 낚시 횟수 : 12회)</p>
            </section></body></html>
        """.trimIndent()

        val snapshot = parser.parse(html, url, forms.parse(html, url))

        assertTrue(snapshot.blockedByBattle)
        assertEquals(FishingPrimaryAction.NONE, snapshot.primaryAction)
    }

    @Test
    fun `낚시 action 결과에만 몬스터 차단 문구가 있어도 전투 상태로 전환한다`() {
        val html = fixture("reset.html")
        val result = ParsedTownResult(
            messages = listOf("낚시터에 나타난 몬스터 때문에 낚시가 불가능합니다!(전투 탭에서 확인)"),
            items = emptyList(),
        )

        val snapshot = parser.parse(html, url, forms.parse(html, url), result)

        assertTrue(snapshot.blockedByBattle)
        assertEquals(FishingPrimaryAction.NONE, snapshot.primaryAction)
        assertTrue(snapshot.availableActions.isEmpty())
    }

    @Test
    fun `상단 공용 전투 링크는 낚시 전투로 오인하지 않는다`() {
        val html = fixture("header-battle.html")
        val snapshot = parser.parse(html, url, forms.parse(html, url))

        assertFalse(snapshot.blockedByBattle)
        assertEquals(null, snapshot.battleTarget)
        assertEquals(FishingPrimaryAction.START, snapshot.primaryAction)
    }

    @Test
    fun `빨간색이 아닌 몬스터 안내와 전투 링크는 낚시 전투로 오인하지 않는다`() {
        val html = """
            <main id="fishing">
              <p>몬스터가 등장하면 낚시를 할 수 없습니다.</p>
              <a href="?menu=hunt&amp;common=ordinary_battle">전투</a>
              <form method="post"><input type="submit" name="do" value="낚시를 시작한다"></form>
            </main>
        """.trimIndent()
        val snapshot = parser.parse(html, url, forms.parse(html, url))

        assertFalse(snapshot.blockedByBattle)
        assertEquals(FishingPrimaryAction.START, snapshot.primaryAction)
        assertEquals(setOf(FishingAction.START), snapshot.availableActions.map { it.action }.toSet())
    }

    @Test
    fun `긴 낚시 안내문은 물 상태나 전투 발생으로 오인하지 않는다`() {
        val html = fixture("guide-with-reset.html")
        val snapshot = parser.parse(html, url, forms.parse(html, url))

        assertFalse(snapshot.blockedByBattle)
        assertEquals(null, snapshot.battleTarget)
        assertEquals(FishingPrimaryAction.START, snapshot.primaryAction)
        assertEquals(18, snapshot.remainingCasts)
        assertEquals("수면이 아름답게 빛나고있다", snapshot.waterStatus)
    }

    @Test
    fun `물고기 상태에서는 중복되는 남은 낚시 횟수를 제거한다`() {
        val html = """
            <main id="fishing">
              <p>오늘의 남은 낚시 횟수 : 18회 (오늘의 남은 낚시 횟수 : 18회) 수면이 아름답게 빛나고있다.</p>
              <form method="post"><input type="submit" name="do" value="낚시를 시작한다"></form>
            </main>
        """.trimIndent()
        val snapshot = parser.parse(html, url, forms.parse(html, url))

        assertEquals(18, snapshot.remainingCasts)
        assertEquals("수면이 아름답게 빛나고있다", snapshot.waterStatus)
    }

    @Test
    fun `낚시 결과 아이템을 이름 수량 사용횟수 효과로 구조화한다`() {
        val html = fixture("caught.html")
        val snapshot = parser.parse(html, url, forms.parse(html, url), results.parse(html))

        assertEquals(FishingOutcome.CAUGHT, snapshot.lastOutcome)
        assertTrue(snapshot.waterStatus.orEmpty().startsWith("낚는다!"))
        assertTrue(snapshot.waterStatus.orEmpty().contains("Rank Fish"))
        assertFalse(snapshot.waterStatus.orEmpty().contains("수면이 아름답게"))
        val item = snapshot.catches.single()
        assertEquals("Rank Fish", item.name)
        assertEquals(2, item.quantity)
        assertEquals(100, item.remainingUses)
        assertTrue(item.effect.orEmpty().contains("HP+3000"))
    }

    @Test
    fun `분리된 분류 form과 정적 script에서 선택 분류의 품목을 복원한다`() {
        val html = fixture("exchange.html")
        val exchange = parser.parseExchange(html, url, forms.parse(html, url), categoryCandidateId = "type_create:useitem")

        assertEquals(listOf("무기(weapon)", "방어구(armor)", "사용가능(useitem)", "전부(all)"), exchange.categories.map { it.label })
        assertEquals("type_create:useitem", exchange.currentCategoryId)
        assertTrue(exchange.items.single { it.name.contains("Rank Fish") }.selectable)
        assertEquals("rank-fish", exchange.items.single().id)
    }

    @Test
    fun `교환 품목의 이름 설명 재료를 구조화한다`() {
        val html = fixture("exchange.html")

        val item = parser.parseExchange(
            html,
            url,
            forms.parse(html, url),
            categoryCandidateId = "type_create:useitem",
        ).items.single()

        assertEquals("Rank Fish (100회 사용가능)", item.name)
        assertEquals("h:2", item.detail)
        assertEquals(listOf("Fishing Coin x 30"), item.materials)
        assertEquals(10L, item.price)
    }

    @Test
    fun `사용 횟수와 보유량이 포함된 여러 교환 재료를 분리한다`() {
        val html = fixture("exchange.html").replace(
            "Fishing Coin x 30",
            "Flat Fish (100회 사용가능) x 1(11.9) Scale Fish (100회 사용가능) x 1(104)",
        )

        val item = parser.parseExchange(
            html,
            url,
            forms.parse(html, url),
            categoryCandidateId = "type_create:useitem",
        ).items.single()

        assertEquals("h:2", item.detail)
        assertEquals(
            listOf("Flat Fish (100회 사용가능) x 1(11.9)", "Scale Fish (100회 사용가능) x 1(104)"),
            item.materials,
        )
    }

    @Test
    fun `현재 분류에 교환품이 없어도 분류 드롭다운은 유지한다`() {
        val html = fixture("exchange.html")

        val exchange = parser.parseExchange(html, url, forms.parse(html, url))

        assertEquals(4, exchange.categories.size)
        assertEquals("type_create:weapon", exchange.currentCategoryId)
        assertTrue(exchange.items.isEmpty())
        assertNotNull(exchange.actionId)
    }

    @Test
    fun `radio 없는 교환품도 선택한 분류에서 숨기지 않고 선택 불가로 둔다`() {
        val html = fixture("exchange.html")

        val exchange = parser.parseExchange(html, url, forms.parse(html, url), categoryCandidateId = "type_create:armor")

        assertEquals("type_create:armor", exchange.currentCategoryId)
        assertFalse(exchange.items.single { it.name.contains("교환 불가 기념 물고기") }.selectable)
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/town/fishing/$name")).readText()
}
