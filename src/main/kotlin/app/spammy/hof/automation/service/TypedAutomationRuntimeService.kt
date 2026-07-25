package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

sealed interface TypedRuntimeClaim {
    data object Inactive : TypedRuntimeClaim
    data object Busy : TypedRuntimeClaim
    data class Acquired(val token: String, val preparedAction: TypedAutomationActionRunEntity? = null) : TypedRuntimeClaim
    data class AmbiguousRecovered(val message: String) : TypedRuntimeClaim
}

@Service
class TypedAutomationRuntimeService(
    private val queryRepository: TypedAutomationQueryRepository,
    private val actionRepository: TypedAutomationActionRunCommandRepository,
    private val codec: StoredTypedAutomationActionCodec,
    private val timeProvider: TimeProvider,
    private val lifecycleBridge: TypedAutomationLifecycleBridge,
    private val outbox: AutomationOutboxService,
) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun isRunning(accountId: Long): Boolean =
        queryRepository.findRuntimeState(accountId)?.lifecycleStatus == TypedAutomationLifecycle.RUNNING

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun start(accountId: Long): Boolean {
        return lifecycleBridge.start(accountId, "TYPED_AUTOMATION_STARTED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resume(accountId: Long) {
        lifecycleBridge.resume(accountId, "TYPED_AUTOMATION_RESUMED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun pause(accountId: Long) {
        lifecycleBridge.pause(accountId, "TYPED_AUTOMATION_PAUSED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stop(accountId: Long, reason: AutomationStopReason) {
        lifecycleBridge.stop(accountId, reason, "TYPED_AUTOMATION_STOPPED")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(accountId: Long): TypedRuntimeClaim {
        val state = queryRepository.lockRuntimeState(accountId) ?: return TypedRuntimeClaim.Inactive
        if (state.lifecycleStatus != TypedAutomationLifecycle.RUNNING) return TypedRuntimeClaim.Inactive
        val now = timeProvider.now()
        if (state.nextAttemptAt?.isAfter(now) == true) return TypedRuntimeClaim.Busy
        if (state.leaseUntil?.isAfter(now) == true) return TypedRuntimeClaim.Busy
        val active = queryRepository.findActiveTypedAction(accountId)
        if (active?.status == TypedAutomationActionStatus.SUBMITTING) {
            active.status = TypedAutomationActionStatus.RECONCILING
            active.finishedAt = null
            active.lastError = "A submitted action lost its lease; verify its authoritative state."
            active.updatedAt = now
        }
        state.nextAttemptAt = null
        state.waitReason = null
        val token = UUID.randomUUID().toString()
        state.leaseToken = token
        state.leaseUntil = now.plus(LEASE_DURATION)
        state.updatedAt = now
        active?.leaseToken = token
        active?.updatedAt = now
        return TypedRuntimeClaim.Acquired(token, active)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun prepare(accountId: Long, token: String, action: StoredTypedAutomationAction): TypedAutomationActionRunEntity? {
        val state = fencedState(accountId, token) ?: return null
        queryRepository.findActiveTypedAction(accountId)?.let { return it }
        val entry = queryRepository.findEntry(accountId, action.entryId) ?: return null
        val encoded = codec.encode(action)
        val now = timeProvider.now()
        state.updatedAt = now
        return actionRepository.save(
            TypedAutomationActionRunEntity(
                account = entry.account, entry = entry, executionIdentity = action.executionIdentity,
                actionKind = action.payload.kind(), payloadJson = encoded.json,
                actionFingerprint = encoded.fingerprint, status = TypedAutomationActionStatus.PREPARED,
                leaseToken = token, createdAt = now, updatedAt = now,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSubmitting(accountId: Long, token: String, actionId: Long): Boolean {
        fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != accountId || action.leaseToken != token || action.status != TypedAutomationActionStatus.PREPARED) return false
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUBMITTING
        action.nextAttemptAt = null
        action.lastError = null
        action.submittedAt = now
        action.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferSubmittedAction(
        accountId: Long,
        token: String,
        actionId: Long,
        retryAt: Instant,
        message: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.SUBMITTING
        ) return false

        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(message)
        action.status = TypedAutomationActionStatus.PREPARED
        action.retryAttempt += 1
        action.nextAttemptAt = retryAt
        action.submittedAt = null
        action.finishedAt = null
        action.lastError = diagnostic
        action.updatedAt = now

        state.nextAttemptAt = retryAt
        state.waitReason = AutomationWaitReason.HOF_CONNECTION
        state.leaseToken = null
        state.leaseUntil = null
        state.lastError = diagnostic
        state.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markReconcilingAndEnqueueWake(
        accountId: Long,
        token: String,
        actionId: Long,
        message: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.SUBMITTING
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(message)
        action.status = TypedAutomationActionStatus.RECONCILING
        action.nextAttemptAt = null
        action.finishedAt = null
        action.lastError = diagnostic
        action.updatedAt = now
        state.leaseToken = null
        state.leaseUntil = null
        state.nextAttemptAt = null
        state.waitReason = null
        state.lastError = diagnostic
        state.updatedAt = now
        outbox.enqueue(accountId, "TYPED_AMBIGUOUS_RECONCILE")
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun retryReconciledSubmission(
        accountId: Long,
        token: String,
        actionId: Long,
        reason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.RECONCILING
        ) return false
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.PREPARED
        action.retryAttempt += 1
        action.nextAttemptAt = null
        action.submittedAt = null
        action.finishedAt = null
        action.lastError = null
        action.updatedAt = now
        state.leaseToken = null
        state.leaseUntil = null
        state.nextAttemptAt = null
        state.waitReason = null
        state.lastError = null
        state.updatedAt = now
        outbox.enqueue(accountId, reason)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferReconciliation(
        accountId: Long,
        token: String,
        actionId: Long,
        retryAt: Instant,
        reason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.RECONCILING
        ) return false
        val now = timeProvider.now()
        val diagnostic = sanitizeDiagnostic(reason)
        action.retryAttempt += 1
        action.nextAttemptAt = retryAt
        action.lastError = diagnostic
        action.updatedAt = now
        state.leaseToken = null
        state.leaseUntil = null
        state.nextAttemptAt = retryAt
        state.waitReason = AutomationWaitReason.HOF_CONNECTION
        state.lastError = diagnostic
        state.updatedAt = now
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeedReconciliation(
        accountId: Long,
        token: String,
        actionId: Long,
        reason: String,
    ): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (
            action.account.id != accountId ||
            action.leaseToken != token ||
            action.status != TypedAutomationActionStatus.RECONCILING
        ) return false
        val now = timeProvider.now()
        action.status = TypedAutomationActionStatus.SUCCEEDED
        action.lastError = null
        action.finishedAt = now
        action.updatedAt = now
        state.retryAttempt = 0
        state.nextAttemptAt = null
        state.waitReason = null
        state.leaseToken = null
        state.leaseUntil = null
        state.warningText = null
        state.lastError = null
        state.stopActionId = null
        state.updatedAt = now
        outbox.enqueue(accountId, reason)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeed(accountId: Long, token: String, actionId: Long): Boolean = finish(accountId, token, actionId, TypedAutomationActionStatus.SUCCEEDED, null)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeedAndEnqueueWake(accountId: Long, token: String, actionId: Long, reason: String): Boolean {
        val succeeded = finish(accountId, token, actionId, TypedAutomationActionStatus.SUCCEEDED, null)
        if (succeeded) outbox.enqueue(accountId, reason)
        return succeeded
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stop(accountId: Long, token: String, actionId: Long?, reason: AutomationStopReason, message: String): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val now = timeProvider.now()
        val stoppedAction = actionId?.let { id ->
            queryRepository.lockTypedAction(id)?.takeIf {
                it.account.id == accountId &&
                    it.leaseToken == token &&
                    it.status in setOf(TypedAutomationActionStatus.PREPARED, TypedAutomationActionStatus.SUBMITTING)
            }
        }
        stoppedAction?.apply {
            status = if (
                status == TypedAutomationActionStatus.SUBMITTING &&
                reason in setOf(AutomationStopReason.NETWORK, AutomationStopReason.CAPTCHA)
            ) {
                TypedAutomationActionStatus.RECONCILING
            } else {
                TypedAutomationActionStatus.FAILED
            }
            lastError = message.take(2000)
            finishedAt = now.takeUnless { status == TypedAutomationActionStatus.RECONCILING }
            updatedAt = now
        }
        state.lastError = sanitizeDiagnostic(message)
        stopState(state, reason, now, stoppedAction?.id)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun stopForIntegrityFailure(accountId: Long, token: String, actionId: Long, message: String): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != accountId || action.leaseToken != token) return false
        val terminalStatus = when (action.status) {
            TypedAutomationActionStatus.PREPARED -> TypedAutomationActionStatus.FAILED
            TypedAutomationActionStatus.RECONCILING -> TypedAutomationActionStatus.AMBIGUOUS
            else -> return false
        }
        val now = timeProvider.now()
        action.status = terminalStatus
        action.lastError = message.take(2000)
        action.finishedAt = now
        action.updatedAt = now
        state.lastError = sanitizeDiagnostic(message)
        stopState(state, AutomationStopReason.FATAL, now, action.id)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun scheduleSafeRetry(accountId: Long, token: String, message: String): Instant? {
        val state = fencedState(accountId, token) ?: return null
        val now = timeProvider.now()
        state.retryAttempt += 1
        state.lastError = sanitizeDiagnostic(message)
        state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
        if (state.retryAttempt >= 4) {
            stopState(state, AutomationStopReason.NETWORK, now)
            return null
        }
        return now.plusSeconds(RETRY_SECONDS[state.retryAttempt - 1]).also {
            state.nextAttemptAt = it
            state.waitReason = AutomationWaitReason.HOF_CONNECTION
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferUntil(
        accountId: Long,
        retryAt: Instant,
        reason: AutomationWaitReason,
    ): Boolean {
        val state = queryRepository.lockRuntimeState(accountId)
            ?.takeIf { it.lifecycleStatus == TypedAutomationLifecycle.RUNNING }
            ?: return false
        state.nextAttemptAt = retryAt
        state.waitReason = reason
        state.updatedAt = timeProvider.now()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun release(
        accountId: Long,
        token: String,
        nextRunAt: Instant? = null,
        waitReason: AutomationWaitReason? = null,
    ): Boolean {
        return releaseCore(accountId, token, nextRunAt, waitReason, emptyList())
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun releaseWithDiagnostics(accountId: Long, token: String, nextRunAt: Instant?, warnings: List<String>): Boolean {
        return releaseCore(
            accountId,
            token,
            nextRunAt,
            nextRunAt?.let { AutomationWaitReason.SCHEDULED },
            warnings,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun releaseAndEnqueueWake(accountId: Long, token: String, reason: String): Boolean {
        val released = releaseCore(accountId, token, null, null, emptyList())
        if (released) outbox.enqueue(accountId, reason)
        return released
    }

    private fun releaseCore(
        accountId: Long,
        token: String,
        nextRunAt: Instant?,
        waitReason: AutomationWaitReason?,
        warnings: List<String>,
    ): Boolean {
        require((nextRunAt == null) == (waitReason == null)) {
            "nextRunAt and waitReason must either both be null or both be present."
        }
        val state = fencedState(accountId, token) ?: return false
        state.leaseToken = null; state.leaseUntil = null; state.nextAttemptAt = nextRunAt; state.waitReason = waitReason
        state.updatedAt = timeProvider.now()
        state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        state.lastError = null
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deferForConfiguration(accountId: Long, token: String, warnings: List<String>): Instant? {
        val state = fencedState(accountId, token) ?: return null
        val next = timeProvider.now().plus(CONFIG_RECHECK)
        state.leaseToken = null; state.leaseUntil = null; state.nextAttemptAt = next
        state.waitReason = AutomationWaitReason.SCHEDULED; state.updatedAt = timeProvider.now()
        state.warningText = warnings.joinToString("\n") { sanitizeDiagnostic(it) }
        state.lastError = null
        return next
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordWarnings(accountId: Long, token: String, warnings: List<String>): Boolean {
        val state = fencedState(accountId, token) ?: return false
        state.warningText = warnings.takeIf { it.isNotEmpty() }?.joinToString("\n") { sanitizeDiagnostic(it) }
        state.updatedAt = timeProvider.now()
        return true
    }

    private fun finish(accountId: Long, token: String, actionId: Long, status: TypedAutomationActionStatus, error: String?): Boolean {
        val state = fencedState(accountId, token) ?: return false
        val action = queryRepository.lockTypedAction(actionId) ?: return false
        if (action.account.id != accountId || action.leaseToken != token || action.status != TypedAutomationActionStatus.SUBMITTING) return false
        val now = timeProvider.now()
        action.status = status; action.lastError = error; action.finishedAt = now; action.updatedAt = now
        state.retryAttempt = 0; state.nextAttemptAt = null; state.waitReason = null
        state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
        state.warningText = null; state.lastError = null
        state.stopActionId = null
        return true
    }

    private fun fencedState(accountId: Long, token: String) = queryRepository.lockRuntimeState(accountId)
        ?.takeIf { it.lifecycleStatus == TypedAutomationLifecycle.RUNNING && it.leaseToken == token }

    private fun stopState(
        state: TypedAutomationRuntimeStateEntity,
        reason: AutomationStopReason,
        now: Instant,
        stopActionId: Long? = null,
    ) {
        state.lifecycleStatus = TypedAutomationLifecycle.STOPPED; state.stopReason = reason.name
        state.stopActionId = stopActionId
        state.nextAttemptAt = null; state.waitReason = null
        state.leaseToken = null; state.leaseUntil = null; state.updatedAt = now
    }

    private fun sanitizeDiagnostic(value: String): String = value
        .replace(Regex("(?i)(password|token|cookie|authorization)\\s*[=:]\\s*[^\\s,;]+"), "$1=[redacted]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .take(2000)

    companion object {
        private val LEASE_DURATION = Duration.ofMinutes(5)
        private val CONFIG_RECHECK = Duration.ofMinutes(5)
        private val RETRY_SECONDS = listOf(10L, 30L, 60L)
    }
}
