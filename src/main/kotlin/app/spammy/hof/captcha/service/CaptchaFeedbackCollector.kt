package app.spammy.hof.captcha.service

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.external.parser.HofMainStatusParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

data class CaptchaFeedbackCandidate(
    val accountId: Long,
    val challengeId: Long,
    val image: CaptchaImageResponse,
    val responseHtml: String,
    val submittedText: String,
    val expectedAccepted: Boolean,
    val source: CaptchaFeedbackSource,
    val predictedText: String?,
    val engineVersion: String,
)

/** 검증된 label만 transaction commit 이후 OCR 서버로 비동기 전송한다. */
@Component
class CaptchaFeedbackCollector(
    private val properties: CaptchaAutoSolveProperties,
    private val statusParser: HofMainStatusParser,
    private val resultParser: CaptchaFeedbackResultParser,
    private val gateway: CaptchaFeedbackGateway,
    @Qualifier("captchaFeedbackTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun collectAfterCommit(candidate: CaptchaFeedbackCandidate) {
        if (!properties.enabled) return

        val playerName = statusParser.parse(candidate.responseHtml).playerName
        val label = resultParser.parse(
            html = candidate.responseHtml,
            currentPlayerName = playerName,
            submittedText = candidate.submittedText,
        ) ?: run {
            log.info(
                "CAPTCHA feedback discarded because no unique verified result was found accountId={} challengeId={}",
                candidate.accountId,
                candidate.challengeId,
            )
            return
        }
        if (label.accepted != candidate.expectedAccepted) {
            log.warn(
                "CAPTCHA feedback discarded because response state conflicts with history accountId={} challengeId={}",
                candidate.accountId,
                candidate.challengeId,
            )
            return
        }

        val feedback = CaptchaFeedback(
            image = CaptchaImageResponse(candidate.image.contentType, candidate.image.bytes.copyOf()),
            predictedText = candidate.predictedText,
            submittedText = candidate.submittedText,
            correctText = label.correctText,
            accepted = label.accepted,
            source = candidate.source,
            engineVersion = candidate.engineVersion,
        )
        afterCommit { dispatch(candidate.accountId, candidate.challengeId, feedback) }
    }

    private fun dispatch(accountId: Long, challengeId: Long, feedback: CaptchaFeedback) {
        try {
            taskExecutor.execute {
                runCatching { gateway.submit(feedback) }
                    .onSuccess {
                        log.info(
                            "CAPTCHA feedback delivered accountId={} challengeId={} accepted={} source={}",
                            accountId,
                            challengeId,
                            feedback.accepted,
                            feedback.source,
                        )
                    }
                    .onFailure { error ->
                        log.warn(
                            "CAPTCHA feedback delivery failed accountId={} challengeId={} errorType={}",
                            accountId,
                            challengeId,
                            error.javaClass.name,
                        )
                    }
            }
        } catch (error: RuntimeException) {
            log.warn(
                "CAPTCHA feedback queue rejected accountId={} challengeId={} errorType={}",
                accountId,
                challengeId,
                error.javaClass.name,
            )
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}
