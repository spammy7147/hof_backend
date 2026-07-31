package app.spammy.hof.town

import app.spammy.hof.town.auction.parser.AuctionPageParser
import app.spammy.hof.town.auction.service.redactAuctionParticipantLine
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.module.kotlin.jacksonObjectMapper

class TownHtmlLeakageTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `HOF 결과는 태그 쿠키 자격증명과 옥션 참여자 이름을 JSON에 노출하지 않는다`() {
        val html = """
            <html><body>
              <div class="result"><b>처리 완료</b><script>window.secret='RAW_SCRIPT'</script></div>
              <div class="result">Set-Cookie: PHPSESSID=COOKIE SECRET/TAIL</div>
              <div class="result">Authorization: Bearer ACCESS/SECRET WITH SPACE</div>
              <div class="result">Bearer STANDALONE_TOKEN_SECRET</div>
              <div class="result">password="PASSWORD SECRET VALUE"</div>
              <div class="result">판매자: Alice Smith / 길드 입찰자: Bob Jones</div>
              <div class="result">Rank Fish를 획득했다</div>
            </body></html>
        """.trimIndent()

        val response = TownActionResultResponse.from(HofResultParser().parse(html))
        val json = mapper.writeValueAsString(response)

        listOf(
            "<html", "<body", "<script", "RAW_SCRIPT", "COOKIE_SECRET", "ACCESS_SECRET",
            "PASSWORD", "STANDALONE_TOKEN_SECRET", "Alice", "Smith", "Bob", "Jones", "PHPSESSID", "Set-Cookie",
            "Authorization", "password",
        ).forEach { forbidden -> assertFalse(json.contains(forbidden, ignoreCase = true), forbidden) }
        assertTrue(json.contains("처리 완료"))
        assertTrue(json.contains("Rank Fish를 획득했다"))
        assertTrue(json.contains("비공개"))
    }

    @Test
    fun `옥션 참여자 줄만 폐기하고 이웃 게임 결과 줄은 보존한다`() {
        val lines = listOf(
            "Potion x2를 획득했다",
            "판매자: Alice Smith / bidder: Bob Jones / 낙찰 완료",
            "Funds 1,000을 수령했다",
        ).map(::redactAuctionParticipantLine)

        assertEquals("Potion x2를 획득했다", lines[0])
        assertEquals("옥션 결과(참여자 정보 비공개)", lines[1])
        assertEquals("Funds 1,000을 수령했다", lines[2])
        assertFalse(lines.joinToString().contains("Alice Smith"))
        assertFalse(lines.joinToString().contains("Bob Jones"))
    }

    @Test
    fun `옥션 JSON은 표와 form 어느 경로에서도 판매자 입찰자 정보를 포함하지 않는다`() {
        val html = """
            <table>
              <tr><th>No</th><th>남은 시간</th><th>가격</th><th>아이템</th><th>입찰</th><th>입찰자</th><th>판매자</th></tr>
              <tr><td>17</td><td>1시간</td><td>${'$'} 12,000</td><td>Potion (item) x2</td><td>1</td><td>PRIVATE_BIDDER</td><td>PRIVATE_SELLER</td></tr>
            </table>
            <form method="post"><input name="ArticleNo"><input name="BidPrice"><input type="submit" name="Bid" value="Bid"></form>
            <form method="post"><label><input type="radio" name="auction" value="lot-7">Elixir (item) x3 / ${'$'} 9,000 / seller: PRIVATE_FORM_SELLER / bidder: PRIVATE_FORM_BIDDER</label><button name="Bid" value="입찰">입찰</button></form>
        """.trimIndent()
        val forms = HofFormParser().parse(html)
        val responses = listOf(AuctionPageParser().parse(html, forms), AuctionPageParser().parse(forms))
        val json = mapper.writeValueAsString(responses)

        assertFalse(json.contains("PRIVATE_"))
        assertFalse(json.contains("seller", ignoreCase = true))
        assertFalse(json.contains("bidder", ignoreCase = true))
    }

    @Test
    fun `town response 계약과 fixture corpus에 전송 비밀 또는 실사용자 식별자가 없다`() {
        val sourceRoot = Path.of("src/main/kotlin/app/spammy/hof/town")
        val fixtureRoot = Path.of("src/test/resources/fixtures/town")
        assertTrue(Files.isDirectory(sourceRoot))
        assertTrue(Files.isDirectory(fixtureRoot))

        val responseContractSource = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" && (it.name.endsWith("Dtos.kt") || it.name == "AuctionController.kt") }
                .map(Files::readString)
                .toList()
                .joinToString("\n")
        }
        val forbiddenProperties = Regex(
            "\\bval\\s+(?:rawHtml|html|cookies?|setCookies|credentials?|password|loginId|seller|bidder|participantName|accountName)\\b",
            RegexOption.IGNORE_CASE,
        )
        assertFalse(forbiddenProperties.containsMatchIn(responseContractSource))

        Files.walk(fixtureRoot).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { fixture ->
                val contents = Files.readString(fixture)
                listOf("PHPSESSID=", "Set-Cookie:", "Authorization: Bearer", "password=", "공민이")
                    .forEach { forbidden -> assertFalse(contents.contains(forbidden, ignoreCase = true), "${fixture}: $forbidden") }
            }
        }
    }
}
