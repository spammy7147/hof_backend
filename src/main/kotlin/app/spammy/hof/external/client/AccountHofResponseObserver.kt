package app.spammy.hof.external.client

import app.spammy.hof.character.service.CharacterRosterObservationService
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 상태·통행증·캐릭터 관측의 순서와 대상별 실패 격리를 소유한다. 실행 시점과 시각은 gateway가 전달한다. */
@Component
class AccountHofResponseObserver(
    private val snapshots: HofStatusSnapshotService,
    private val characterRosters: CharacterRosterObservationService,
    private val passMaintenance: CaptchaPassMaintenanceService? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun observe(
        accountId: Long,
        response: HofHttpResponse,
        requestStartedAt: Instant,
        responseObservedAt: Instant,
        observeCharacterRoster: Boolean,
    ) {
        runCatching { snapshots.observe(accountId, response.body, requestStartedAt) }
            .onFailure { error ->
                log.warn("HOF status observation failed accountId={}", accountId, error)
            }
        runCatching { passMaintenance?.observe(accountId, response.body, requestStartedAt, responseObservedAt) }
            .onFailure { error ->
                log.warn("HOF pass observation failed accountId={}", accountId, error)
            }
        if (!observeCharacterRoster) return
        runCatching { characterRosters.observe(accountId, response, requestStartedAt) }
            .onFailure { error ->
                log.warn("HOF character roster observation failed accountId={}", accountId, error)
            }
    }
}
