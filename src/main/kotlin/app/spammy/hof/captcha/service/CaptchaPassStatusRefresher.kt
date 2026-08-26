package app.spammy.hof.captcha.service

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.status.service.HofStatusService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import org.springframework.stereotype.Service

/** 자동 갱신 실행마다 저장된 HOF 자격 증명 복구를 한 번 허용하고 홈 상태를 새로 관측한다. */
@Service
class CaptchaPassStatusRefresher(
    private val recovery: HofSessionRecoveryService,
    private val status: HofStatusService,
    private val maintenance: CaptchaPassMaintenanceService,
    private val timeProvider: TimeProvider,
) {
    fun refresh(accountId: Long) {
        val refreshStartedAt = timeProvider.now()
        recovery.execute(accountId, HofRequestOrigin.AUTOMATION) {
            status.fetch(accountId, HofRequestOrigin.AUTOMATION)
        }
        val observed = maintenance.get(accountId)
        check(observed.observedAt?.isBefore(refreshStartedAt) == false) {
            "HOF 상태 조회에서 최신 통행증 표시를 관측하지 못했습니다."
        }
        if (observed.passState == CaptchaPassMaintenanceEntity.PASS_VALID) {
            check(observed.remainingSeconds?.let { it > 0 } == true) {
                "HOF 상태 조회의 통행증 유효시간이 이미 만료되었습니다."
            }
        }
    }
}
