package app.spammy.hof.captcha.service

import app.spammy.hof.automation.service.TypedCaptchaAutomationResumeService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** HOF 답안을 재제출하지 않고 commit된 답안의 관문 해제·재개 의도만 복구한다. */
@Component
@Profile("dev | prod")
class CaptchaAutomationResumeScheduler(private val resumes: TypedCaptchaAutomationResumeService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() = runPending()

    @Scheduled(fixedDelay = 5000)
    fun runPending() {
        resumes.findPendingAccountIds().forEach { accountId ->
            try {
                resumes.resumeAfterCaptcha(accountId)
            } catch (exception: Exception) {
                log.warn("Captcha automation resume recovery failed accountId={} errorType={}", accountId, exception.javaClass.name)
            }
        }
    }
}
