package app.spammy.hof.external.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaidCooldownFixtureSanitizerTest {
    private val sanitizer = RaidCooldownFixtureSanitizer()
    private val parser = BattleMapParser()

    @Test
    fun `운영 HTML은 cooldown과 map 조상 관계만 보존하고 인증정보와 개인 텍스트를 제거한다`() {
        val rawHtml = """
            <html><body data-session="session-secret">
              <script>window.token = 'script-secret'</script>
              <section id="raid-user-alice" class="raid-card account-alice">
                <form action="index.php?token=form-secret">
                  <input type="hidden" name="csrf" value="csrf-secret">
                  <div class="map-row">
                    <span class="member-name">Alice Character</span>
                    <span class="raid-timer">다음 전투까지 93초 남음</span>
                    <a href="index.php?raid_common=RaidGoblin&amp;token=link-secret">Raid - Alice Goblin</a>
                  </div>
                </form>
              </section>
            </body></html>
        """.trimIndent()

        val sanitized = assertNotNull(sanitizer.sanitize(rawHtml))

        assertTrue(sanitized.html.contains("다음 전투까지 93초 남음"))
        assertTrue(sanitized.html.contains("raid_common=fixture-map-1"))
        assertTrue(sanitized.html.contains("class=\"raid-card\""))
        assertTrue(sanitized.html.contains("class=\"map-row\""))
        assertFalse(sanitized.html.contains("Alice"))
        assertFalse(sanitized.html.contains("secret"))
        assertFalse(sanitized.html.contains("<input"))
        assertFalse(sanitized.html.contains("<script"))
        assertFalse(sanitized.html.contains("RaidGoblin"))

        val rawMaps = parser.parse("raid", "raid_common", rawHtml)
        val sanitizedMaps = parser.parse("raid", "raid_common", sanitized.html)
        assertEquals(
            parser.inspectRaidCooldown(rawHtml, rawMaps).status,
            parser.inspectRaidCooldown(sanitized.html, sanitizedMaps).status,
        )
        assertEquals(rawMaps.single().cooldownRemainingSeconds, sanitizedMaps.single().cooldownRemainingSeconds)
    }

    @Test
    fun `cooldown 또는 raid map이 없으면 fixture를 만들지 않는다`() {
        assertEquals(null, sanitizer.sanitize("<div>일반 전투 페이지</div>"))
        assertEquals(null, sanitizer.sanitize("<div>다음 전투까지 10초 남음</div>"))
    }
}
