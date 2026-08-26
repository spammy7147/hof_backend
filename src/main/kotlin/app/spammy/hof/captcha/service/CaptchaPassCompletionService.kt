package app.spammy.hof.captcha.service

import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 수동 캡차 답안 성공 뒤에도 HOF 표시 countdown을 다시 읽어 실제 통행증 발급을 확인한다. */
@Service
class CaptchaPassCompletionService(
    private val refresher: CaptchaPassStatusRefresher,
    private val maintenance: CaptchaPassMaintenanceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun confirmAfterAnswer(accountId: Long) {
        val refreshed = runCatching {
            refresher.refresh(accountId)
            maintenance.get(accountId)
        }
            .onFailure { error ->
                val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
                maintenance.scheduleConfirmationRetry(accountId, reason)
                log.warn("Pass confirmation after manual answer failed accountId={}", accountId, error)
            }
            .getOrNull()
            ?: return
        if (refreshed.passState != CaptchaPassMaintenanceEntity.PASS_VALID) {
            maintenance.scheduleConfirmationRetry(accountId, PASS_NOT_CONFIRMED)
        }
    }

    private companion object {
        const val PASS_NOT_CONFIRMED = "pass-not-confirmed"
    }
}
