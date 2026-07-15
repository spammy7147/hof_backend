package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.AdventureDailyPreflightStateEntity
import app.spammy.hof.automation.entity.AdventureDailyRefreshEntity
import app.spammy.hof.automation.repository.AdventureDailyPreflightQueryRepository
import app.spammy.hof.automation.repository.AdventureDailyPreflightStateCommandRepository
import app.spammy.hof.automation.repository.AdventureDailyRefreshCommandRepository
import app.spammy.hof.battle.service.AdventureMapRefreshException
import app.spammy.hof.battle.service.AdventureMapSnapshot
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.BattleMapCatalogService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Account-wide gate that Task 9 must call before evaluating any automation handler.
 *
 * The database is touched only in short claim/finalize transactions. The HOF request and map synchronization run after
 * the durable claim commits, so no account lock or database connection is held across network I/O.
 */
@Service
class AutomationDailyPreflight(
    private val accountQueryRepository: AccountQueryRepository,
    private val queryRepository: AdventureDailyPreflightQueryRepository,
    private val refreshRepository: AdventureDailyRefreshCommandRepository,
    private val stateRepository: AdventureDailyPreflightStateCommandRepository,
    private val battleMapService: BattleMapService,
    private val timeProvider: TimeProvider,
    transactionManager: PlatformTransactionManager,
) {
    private val orchestration = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
    }
    private val persistence = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    sealed interface Result {
        data object Ready : Result
        data class Busy(val retryAt: Instant) : Result
        data class RetryScheduled(val nextAttemptAt: Instant, val retryAttempt: Int) : Result
        data class Stopped(val reason: StopReason) : Result
    }

    enum class StopReason { NETWORK, FATAL }

    fun ensureReady(accountId: Long): Result = orchestration.execute { orchestrate(accountId) }

    private fun orchestrate(accountId: Long): Result {
        val claim = persistence.execute { claim(accountId) }
        if (claim is ClaimDecision.Resolved) return claim.result
        claim as ClaimDecision.Claimed

        val snapshot = try {
            battleMapService.fetchAdventureMapSnapshot(accountId)
        } catch (error: Exception) {
            val completedAt = timeProvider.now()
            val completed = CompletedRefresh(classify(error), completedAt, completedAt.koreaDate())
            return persistence.execute { finalize(accountId, claim, completed, snapshot = null) }
        }
        val completedAt = timeProvider.now()
        val completed = CompletedRefresh(RefreshOutcome.Success, completedAt, completedAt.koreaDate())

        return try {
            BattleMapCatalogService.withSynchronizationFence {
                persistence.execute { finalize(accountId, claim, completed, snapshot) }
            }
        } catch (_: Exception) {
            persistence.execute { finalizeSynchronizationFailure(accountId, claim) }
        }
    }

    /** Explicit lifecycle seam for the future manual-resume endpoint. */
    fun resume(accountId: Long) {
        persistence.executeWithoutResult {
            val state = lockAccountAndState(accountId).second ?: return@executeWithoutResult
            state.failedAttempts = 0
            state.nextAttemptAt = null
            state.stopReason = null
            state.inFlightToken = null
            state.inFlightUntil = null
            state.updatedAt = timeProvider.now()
            stateRepository.save(state)
        }
    }

    /** Called inside the claim transaction. Current time is intentionally captured only after the account lock. */
    private fun claim(accountId: Long): ClaimDecision {
        val (account, existingState) = lockAccountAndState(accountId)
        val now = timeProvider.now()
        val koreaDate = now.koreaDate()
        if (queryRepository.hasSuccessfulRefresh(accountId, koreaDate)) {
            return ClaimDecision.Resolved(Result.Ready)
        }

        var state = existingState
        state?.stopReason?.let { return ClaimDecision.Resolved(Result.Stopped(StopReason.valueOf(it))) }
        if (state == null) {
            state = AdventureDailyPreflightStateEntity(
                account = account,
                refreshDate = koreaDate,
                failedAttempts = 0,
                nextAttemptAt = null,
                stopReason = null,
                inFlightToken = null,
                inFlightUntil = null,
                updatedAt = now,
            )
        } else if (state.refreshDate != koreaDate) {
            resetForDate(state, koreaDate, now)
        }

        state.nextAttemptAt?.takeIf { it.isAfter(now) }?.let {
            return ClaimDecision.Resolved(Result.RetryScheduled(it, state.failedAttempts))
        }
        state.inFlightUntil?.takeIf { state.inFlightToken != null && it.isAfter(now) }?.let {
            return ClaimDecision.Resolved(Result.Busy(it))
        }

        val token = UUID.randomUUID().toString()
        state.inFlightToken = token
        state.inFlightUntil = now.plus(IN_FLIGHT_LEASE)
        state.updatedAt = now
        stateRepository.save(state)
        stateRepository.flush()
        return ClaimDecision.Claimed(token, koreaDate)
    }

    /** Called in a fresh transaction after the external request/map-sync transaction has completed or rolled back. */
    private fun finalize(
        accountId: Long,
        claim: ClaimDecision.Claimed,
        completed: CompletedRefresh,
        snapshot: AdventureMapSnapshot?,
    ): Result {
        val (account, state) = lockAccountAndState(accountId)
        val lockedAt = timeProvider.now()
        val koreaDate = lockedAt.koreaDate()

        if (state == null || state.inFlightToken != claim.token) {
            return currentResult(accountId, state, koreaDate, lockedAt)
        }

        if (completed.koreaDate != claim.refreshDate || koreaDate != claim.refreshDate) {
            resetForDate(state, koreaDate, lockedAt)
            stateRepository.save(state)
            return Result.Busy(lockedAt)
        }

        state.updatedAt = lockedAt
        return when (completed.outcome) {
            RefreshOutcome.Success -> finalizeSuccess(
                accountId,
                account,
                state,
                koreaDate,
                completed.completedAt,
                requireNotNull(snapshot),
            )
            RefreshOutcome.RetryableFailure -> finalizeRetryableFailure(state, completed.completedAt)
            RefreshOutcome.FatalFailure -> {
                clearClaim(state)
                state.stopReason = StopReason.FATAL.name
                state.nextAttemptAt = null
                stateRepository.save(state)
                Result.Stopped(StopReason.FATAL)
            }
        }
    }

    private fun finalizeSuccess(
        accountId: Long,
        account: HofAccountEntity,
        state: AdventureDailyPreflightStateEntity,
        koreaDate: LocalDate,
        now: Instant,
        snapshot: AdventureMapSnapshot,
    ): Result {
        require(snapshot.accountId == accountId) { "Adventure snapshot account does not match its preflight claim." }
        battleMapService.synchronizeAdventureMapSnapshot(snapshot)
        if (!queryRepository.hasSuccessfulRefresh(accountId, koreaDate)) {
            refreshRepository.save(
                AdventureDailyRefreshEntity(account = account, refreshDate = koreaDate, refreshedAt = now),
            )
            refreshRepository.flush()
        }
        state.failedAttempts = 0
        state.nextAttemptAt = null
        clearClaim(state)
        stateRepository.save(state)
        return Result.Ready
    }

    private fun finalizeRetryableFailure(state: AdventureDailyPreflightStateEntity, now: Instant): Result {
        clearClaim(state)
        state.failedAttempts += 1
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

    /** Runs only after the token-fenced synchronization transaction rolled back. */
    private fun finalizeSynchronizationFailure(accountId: Long, claim: ClaimDecision.Claimed): Result {
        val (_, state) = lockAccountAndState(accountId)
        val now = timeProvider.now()
        val koreaDate = now.koreaDate()
        if (state == null || state.inFlightToken != claim.token) {
            return currentResult(accountId, state, koreaDate, now)
        }
        if (koreaDate != claim.refreshDate) {
            resetForDate(state, koreaDate, now)
            stateRepository.save(state)
            return Result.Busy(now)
        }
        clearClaim(state)
        state.stopReason = StopReason.FATAL.name
        state.nextAttemptAt = null
        state.updatedAt = now
        stateRepository.save(state)
        return Result.Stopped(StopReason.FATAL)
    }

    private fun currentResult(
        accountId: Long,
        state: AdventureDailyPreflightStateEntity?,
        koreaDate: LocalDate,
        now: Instant,
    ): Result {
        if (queryRepository.hasSuccessfulRefresh(accountId, koreaDate)) return Result.Ready
        state?.stopReason?.let { return Result.Stopped(StopReason.valueOf(it)) }
        state?.nextAttemptAt?.takeIf { it.isAfter(now) }?.let {
            return Result.RetryScheduled(it, state.failedAttempts)
        }
        state?.inFlightUntil?.takeIf { state.inFlightToken != null && it.isAfter(now) }?.let {
            return Result.Busy(it)
        }
        return Result.Busy(now)
    }

    private fun lockAccountAndState(accountId: Long): Pair<HofAccountEntity, AdventureDailyPreflightStateEntity?> {
        val account = accountQueryRepository.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return account to queryRepository.findState(accountId)
    }

    private fun resetForDate(state: AdventureDailyPreflightStateEntity, date: LocalDate, now: Instant) {
        state.refreshDate = date
        state.failedAttempts = 0
        state.nextAttemptAt = null
        state.inFlightToken = null
        state.inFlightUntil = null
        state.updatedAt = now
    }

    private fun clearClaim(state: AdventureDailyPreflightStateEntity) {
        state.inFlightToken = null
        state.inFlightUntil = null
    }

    private fun classify(error: Exception): RefreshOutcome {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        if (causes.any { it is InterruptedException }) {
            Thread.currentThread().interrupt()
            return RefreshOutcome.FatalFailure
        }
        causes.filterIsInstance<AdventureMapRefreshException>().firstOrNull()?.let { typed ->
            return when (typed) {
                is AdventureMapRefreshException.Retryable -> RefreshOutcome.RetryableFailure
                is AdventureMapRefreshException.Fatal -> RefreshOutcome.FatalFailure
            }
        }
        return if (causes.any { it is IOException }) {
            RefreshOutcome.RetryableFailure
        } else {
            RefreshOutcome.FatalFailure
        }
    }

    private fun Instant.koreaDate(): LocalDate = LocalDate.ofInstant(this, KOREA_ZONE)

    private sealed interface ClaimDecision {
        data class Claimed(val token: String, val refreshDate: LocalDate) : ClaimDecision
        data class Resolved(val result: Result) : ClaimDecision
    }

    private data class CompletedRefresh(
        val outcome: RefreshOutcome,
        val completedAt: Instant,
        val koreaDate: LocalDate,
    )

    private enum class RefreshOutcome { Success, RetryableFailure, FatalFailure }

    private companion object {
        val KOREA_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val IN_FLIGHT_LEASE: Duration = Duration.ofSeconds(45)
        val RETRY_DELAYS: List<Duration> = listOf(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
        )
        const val MAX_ATTEMPTS = 4
    }
}
