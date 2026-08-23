package app.spammy.hof.battle.service

import app.spammy.hof.automation.config.RaidAutomationProperties
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.parser.RaidCooldownFixtureSanitizer
import app.spammy.hof.external.parser.RaidCooldownPageObservation
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

fun interface RaidCooldownFixtureProbe {
    fun capture(rawHtml: String, observation: RaidCooldownPageObservation): Boolean

    data object Disabled : RaidCooldownFixtureProbe {
        override fun capture(rawHtml: String, observation: RaidCooldownPageObservation): Boolean = false
    }
}

/**
 * 명시적으로 활성화한 짧은 운영 관측 동안만 익명화 fixture를 구조화 로그로 남긴다.
 * 캡처 실패와 로그 제한 도달은 자동화 판단에 영향을 주지 않는다.
 */
@Component
class LoggingRaidCooldownFixtureProbe(
    private val sanitizer: RaidCooldownFixtureSanitizer,
    private val properties: RaidAutomationProperties,
    private val timeProvider: TimeProvider,
) : RaidCooldownFixtureProbe {
    private val log = LoggerFactory.getLogger(LoggingRaidCooldownFixtureProbe::class.java)
    private val capturedShapes = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    override fun capture(rawHtml: String, observation: RaidCooldownPageObservation): Boolean {
        if (!properties.fixtureProbeEnabled) return false
        if (capturedShapes.size >= MAX_RESPONSE_SHAPES) return false
        if (observation.responseShapeFingerprint in capturedShapes) return false

        val fixture = runCatching { sanitizer.sanitize(rawHtml) }.getOrNull() ?: return false
        if (!capturedShapes.add(observation.responseShapeFingerprint)) return false

        val payload = Base64.getEncoder().encodeToString(fixture.html.toByteArray(Charsets.UTF_8))
        log.info(
            "RAID_COOLDOWN_FIXTURE_PROBE responseShape={} domShape={} observedAt={} mapCount={} " +
                "timerCount={} payloadBase64={}",
            observation.responseShapeFingerprint,
            observation.domFingerprint,
            timeProvider.now(),
            fixture.mapCount,
            fixture.timerCount,
            payload,
        )
        return true
    }

    private companion object {
        const val MAX_RESPONSE_SHAPES = 5
    }
}
