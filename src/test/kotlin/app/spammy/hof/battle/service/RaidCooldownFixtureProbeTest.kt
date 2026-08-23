package app.spammy.hof.battle.service

import app.spammy.hof.automation.config.RaidAutomationProperties
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.parser.RaidCooldownAssociationStatus
import app.spammy.hof.external.parser.RaidCooldownFixtureSanitizer
import app.spammy.hof.external.parser.RaidCooldownPageObservation
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class RaidCooldownFixtureProbeTest {
    private val rawHtml = """
        <html><body data-session="session-secret">
          <div class="raid-row"><span>다음 전투까지 93초 남음</span>
            <a href="index.php?raid_common=real-map&amp;token=secret">Alice raid</a>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `활성 probe는 응답 형태별 익명 fixture를 한 번만 기록한다`(output: CapturedOutput) {
        val probe = LoggingRaidCooldownFixtureProbe(
            RaidCooldownFixtureSanitizer(),
            RaidAutomationProperties(fixtureProbeEnabled = true),
            TimeProvider { Instant.parse("2026-08-23T00:00:00Z") },
        )

        assertTrue(probe.capture(rawHtml, observation("same-shape")))
        assertFalse(probe.capture(rawHtml, observation("same-shape")))

        val markerLine = output.out.lineSequence().single { it.contains("RAID_COOLDOWN_FIXTURE_PROBE") }
        val payload = assertNotNull(Regex("""payloadBase64=([^\s]+)""").find(markerLine)?.groupValues?.get(1))
        val decoded = String(Base64.getDecoder().decode(payload))
        assertTrue(markerLine.contains("responseShape=same-shape"))
        assertTrue(markerLine.contains("observedAt=2026-08-23T00:00:00Z"))
        assertTrue(decoded.contains("다음 전투까지 93초 남음"))
        assertFalse(decoded.contains("Alice"))
        assertFalse(decoded.contains("secret"))
    }

    @Test
    fun `비활성 probe는 fixture를 만들거나 기록하지 않는다`(output: CapturedOutput) {
        val probe = LoggingRaidCooldownFixtureProbe(
            RaidCooldownFixtureSanitizer(),
            RaidAutomationProperties(fixtureProbeEnabled = false),
            TimeProvider { Instant.parse("2026-08-23T00:00:00Z") },
        )

        assertFalse(probe.capture(rawHtml, observation("disabled-shape")))

        assertEquals(0, output.out.lineSequence().count { it.contains("RAID_COOLDOWN_FIXTURE_PROBE") })
    }

    private fun observation(shape: String) = RaidCooldownPageObservation(
        status = RaidCooldownAssociationStatus.AMBIGUOUS,
        candidateSeconds = listOf(93),
        mapCount = 1,
        candidateCount = 1,
        domFingerprint = "dom-shape",
        responseShapeFingerprint = shape,
        reasonCode = "RAID_COOLDOWN_ASSOCIATION_AMBIGUOUS",
    )
}
