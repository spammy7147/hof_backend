package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.AdventureDailyPreflightStateEntity
import app.spammy.hof.automation.entity.AdventureDailyRefreshEntity
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightStateCommandRepository
import app.spammy.hof.automation.repository.AdventureDailyRefreshCommandRepository
import app.spammy.hof.battle.service.AdventureMapRefreshException
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Account-wide gate that must be ready before Task 9 evaluates any automation handler.
 *
 * This service intentionally does not invoke a handler or mutate an automation job. The future coordinator should call
 * [ensureReady] first and continue only for [Result.Ready]. A scheduled retry and a manual stop are persisted here so a
 * different worker observes the same decision.
 */
@Service
class AutomationDailyPreflight(
    private val accountQueryRepository: AccountQueryRepository,
    private val queryRepository: AdventureDailyPreflightQueryRepository,
    private val refreshRepository: AdventureDailyRefreshCommandRepository,
    private val stateRepository: AdventureDailyPreflightStateCommandRepository,
    private val battleMapService: BattleMapService,
    private val timeProvider: TimeProvider,
) {
    sealed interface Result {
        data object Ready : Result
        data class RetryScheduled(val nextAttemptAt: Instant, val retryAttempt: Int) : Result
        data class Stopped(val reason: StopReason) : Result
    }

    enum class StopReason { NETWORK, FATAL }

    /**
     * Locks the account row through the external refresh and success-marker insert. This serializes independent workers,
     * while the database unique constraint remains the final invariant for one success marker per account/date.
     */
    @Transactional
    fun ensureReady(accountId: Long): Result {
        val now = timeProvider.now()
        val koreaDate = LocalDate.ofInstant(now, KOREA_ZONE)
        val account = accountQueryRepository.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")

        if (queryRepository.hasSuccessfulRefresh(accountId, koreaDate)) return Result.Ready

        var state = queryRepository.findState(accountId)
        state?.stopReason?.let { return Result.Stopped(StopReason.valueOf(it)) }

        if (state == null) {
            state = AdventureDailyPreflightStateEntity(
                account = account,
                refreshDate = koreaDate,
                failedAttempts = 0,
                nextAttemptAt = null,
                stopReason = null,
                updatedAt = now,
            )
        } else if (state.refreshDate != koreaDate) {
            state.refreshDate = koreaDate
            state.failedAttempts = 0
            state.nextAttemptAt = null
            state.updatedAt = now
        }

        state.nextAttemptAt?.takeIf { it.isAfter(now) }?.let {
            return Result.RetryScheduled(it, state.failedAttempts)
        }

        try {
            battleMapService.refreshAdventureMaps(accountId)
        } catch (error: Exception) {
            return recordFailure(state, error, now)
        }

        refreshRepository.save(
            AdventureDailyRefreshEntity(account = account, refreshDate = koreaDate, refreshedAt = now),
        )
        refreshRepository.flush()
        state.failedAttempts = 0
        state.nextAttemptAt = null
        state.updatedAt = now
        stateRepository.save(state)
        return Result.Ready
    }

    /** Explicit lifecycle seam for the future manual-resume endpoint. */
    @Transactional
    fun resume(accountId: Long) {
        accountQueryRepository.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        queryRepository.findState(accountId)?.let { state ->
            state.failedAttempts = 0
            state.nextAttemptAt = null
            state.stopReason = null
            state.updatedAt = timeProvider.now()
            stateRepository.save(state)
        }
    }

    private fun recordFailure(
        state: AdventureDailyPreflightStateEntity,
        error: Exception,
        now: Instant,
    ): Result {
        if (!error.isNetworkFailure()) {
            state.stopReason = StopReason.FATAL.name
            state.nextAttemptAt = null
            state.updatedAt = now
            stateRepository.save(state)
            return Result.Stopped(StopReason.FATAL)
        }

        state.failedAttempts += 1
        state.updatedAt = now
        if (state.failedAttempts >= MAX_ATTEMPTS) {
            state.stopReason = StopReason.NETWORK.name
            state.nextAttemptAt = null
            stateRepository.save(state)
            return Result.Stopped(StopReason.NETWORK)
        }

        val retryAttempt = state.failedAttempts
        val nextAttemptAt = now.plus(RETRY_DELAYS[retryAttempt - 1])
        state.nextAttemptAt = nextAttemptAt
        stateRepository.save(state)
        return Result.RetryScheduled(nextAttemptAt, retryAttempt)
    }

    private fun Throwable.isNetworkFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is AdventureMapRefreshException.Retryable ||
                current is IOException || current is InterruptedException
            ) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        val KOREA_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val RETRY_DELAYS: List<Duration> = listOf(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
        )
        const val MAX_ATTEMPTS = 4
    }
}
