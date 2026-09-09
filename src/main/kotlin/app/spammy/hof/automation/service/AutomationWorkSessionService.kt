package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.config.AutomationSessionProperties
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal const val FISHING_CYCLE_TARGET = "DAILY_FISHING"

interface AutomationWorkLifecycle {
    fun resumeForCheck(accountId: Long, sessionId: Long)
    fun handoffForPriority(accountId: Long, currentSessionId: Long, dueSessionId: Long): Boolean
    fun triggerCheck(accountId: Long, sessionId: Long)
    fun yieldForPriority(accountId: Long, sessionId: Long): Boolean
    fun applyTransition(accountId: Long, sessionId: Long, transition: AutomationWorkTransition)
    fun waitForCooldown(accountId: Long, sessionId: Long, nextCheckAt: java.time.Instant)
    fun waitForRaid(
        accountId: Long,
        entryId: Long,
        raidId: String,
        nextCheckAt: java.time.Instant?,
        holdMessage: String? = null,
    )
    fun triggerRaidConfigurationCheck(accountId: Long)
    fun completeBattleMapAction(accountId: Long, entryId: Long, categoryId: String, mapCode: String, executionIdentity: String? = null)
    fun completeAdventureAction(accountId: Long, entryId: Long, categoryId: String, mapCode: String, executionIdentity: String? = null)
    fun waitFishingCycle(
        accountId: Long,
        entryId: Long,
        nextCheckAt: java.time.Instant?,
        holdMessage: String,
    )
    fun completeFishingCycle(accountId: Long, entryId: Long, executionIdentity: String? = null)
    fun completeUnionCycle(accountId: Long, entryId: Long, executionIdentity: String? = null)
    fun completeRaidCycle(accountId: Long, entryId: Long, executionIdentity: String? = null)
    fun stopForConfigurationChange(
        accountId: Long,
        entryId: Long,
        targetKeys: Set<String>,
        wholeEntry: Boolean,
    )
}

@Service
class AutomationWorkSessionService(
    private val typed: TypedAutomationQueryRepository,
    private val queries: AutomationWorkSessionQueryRepository,
    private val commands: AutomationWorkSessionCommandRepository,
    private val timeProvider: TimeProvider,
    private val properties: AutomationSessionProperties = AutomationSessionProperties(),
    private val progressTelemetry: AutomationProgressTelemetry? = null,
) : AutomationWorkOwnership, AutomationWorkLifecycle {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun handoffForPriority(
        accountId: Long,
        currentSessionId: Long,
        dueSessionId: Long,
    ): Boolean {
        requireRunningRuntime(accountId)
        val now = timeProvider.now()
        val open = queries.lockOpen(accountId)
        val current = open.singleOrNull { it.id == currentSessionId } ?: return false
        val due = open.singleOrNull { it.id == dueSessionId } ?: return false
        if (current.status != AutomationWorkStatus.RUNNING) return false
        require(current.workType != AutomationWorkType.RAID) {
            "An immediately runnable raid work session keeps priority until it waits or completes."
        }
        require(
            due.status in setOf(
                AutomationWorkStatus.YIELDED_PRIORITY,
                AutomationWorkStatus.WAITING_COOLDOWN,
                AutomationWorkStatus.WAITING_RESOURCE,
            ) && due.nextCheckAt?.isAfter(now) == false,
        ) { "Only an explicitly due parked work session may receive priority ownership." }

        val transfer = releaseOwnership(open, due, now)
        resumeAsOwner(due, now)
        logOwnershipTransfer(accountId, transfer, due, AutomationOwnershipTransferReason.DUE_RESUME)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun resumeForCheck(accountId: Long, sessionId: Long) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        typed.findEntry(accountId, session.entry.id)
            ?: throw AutomationConfigurationException("Automation entry ${session.entry.id} is missing.")
        require(
            session.status == AutomationWorkStatus.YIELDED_PRIORITY ||
                session.status == AutomationWorkStatus.WAITING_COOLDOWN ||
                session.status == AutomationWorkStatus.WAITING_RESOURCE,
        ) { "Only a parked work session may be resumed." }
        val now = timeProvider.now()
        val open = queries.lockOpen(accountId)
        val transfer = releaseOwnership(open, session, now)
        resumeAsOwner(session, now)
        logOwnershipTransfer(accountId, transfer, session, AutomationOwnershipTransferReason.DUE_RESUME)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun triggerCheck(accountId: Long, sessionId: Long) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(
            session.status == AutomationWorkStatus.WAITING_COOLDOWN ||
                session.status == AutomationWorkStatus.WAITING_RESOURCE,
        ) { "Only a timed waiting session may be triggered." }
        val now = timeProvider.now()
        session.nextCheckAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun ensure(
        accountId: Long,
        entryId: Long,
        assignment: AutomationWorkAssignment,
    ) {
        ensureAssignment(accountId, entryId, assignment.resolveConfiguredTarget(entryId))
    }

    private fun AutomationWorkAssignment.resolveConfiguredTarget(entryId: Long): AutomationWorkAssignment {
        if (type != AutomationWorkType.BATTLE_MAP || targetCount != null) return this
        val configuredTarget = typed.findBattleSettings(entryId)
            .singleOrNull { "${it.categoryId}/${it.mapCode}" == targetKey }
            ?.dailyTargetCount
            ?: throw AutomationConfigurationException("Battle-map target $targetKey is missing or duplicated.")
        return copy(targetCount = configuredTarget)
    }

    private fun ensureAssignment(
        accountId: Long,
        entryId: Long,
        spec: AutomationWorkAssignment,
    ): AutomationWorkSessionEntity {
        requireRunningRuntime(accountId)
        val entry = typed.findEntry(accountId, entryId)
            ?: throw AutomationConfigurationException("Automation entry $entryId is missing.")
        val open = queries.lockOpen(accountId)
        val selected = open.asReversed().firstOrNull {
            it.status == AutomationWorkStatus.RUNNING && it.matches(entryId, spec)
        } ?: open.firstOrNull {
            it.status != AutomationWorkStatus.RUNNING && it.matches(entryId, spec)
        }
        val now = timeProvider.now()
        if (selected != null) {
            val transfer = releaseOwnership(open, selected, now)
            if (selected.status == AutomationWorkStatus.RUNNING) {
                selected.alignRaidTarget(spec, entry.updatedAt.toString())?.let(commands::save)
                logOwnershipTransfer(accountId, transfer, selected, AutomationOwnershipTransferReason.ACTION_PREPARE)
                return selected
            }
            resumeAsOwner(selected, now)
            selected.alignRaidTarget(spec, entry.updatedAt.toString())
            logOwnershipTransfer(accountId, transfer, selected, AutomationOwnershipTransferReason.ACTION_PREPARE)
            return selected
        }
        val transfer = releaseOwnership(open, null, now)
        val session = AutomationWorkSessionEntity(
            account = entry.account,
            entry = entry,
            workType = spec.type,
            targetKey = spec.targetKey,
            status = AutomationWorkStatus.RUNNING,
            configVersion = entry.updatedAt.toString(),
            targetCount = spec.targetCount,
            createdAt = now,
            updatedAt = now,
        )
        commands.save(session)
        logOwnershipTransfer(accountId, transfer, session, AutomationOwnershipTransferReason.ACTION_PREPARE)
        return session
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun yieldForPriority(accountId: Long, sessionId: Long): Boolean {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.workType != AutomationWorkType.RAID) {
            "An immediately runnable raid work session keeps priority until it waits or completes."
        }
        if (session.status != AutomationWorkStatus.RUNNING) return false
        val now = timeProvider.now()
        session.transitionTo(AutomationWorkStatus.YIELDED_PRIORITY)
        session.nextCheckAt = now.plusSeconds(PRIORITY_YIELD_RECHECK_SECONDS)
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.PRIORITY_YIELD, false)
        log.info(
            "Automation work ownership released accountId={} sessionId={} entryId={} reason={}",
            accountId,
            session.id,
            session.entry.id,
            AutomationOwnershipTransferReason.PRIORITY_YIELD,
        )
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun applyTransition(
        accountId: Long,
        sessionId: Long,
        transition: AutomationWorkTransition,
    ) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.status == AutomationWorkStatus.RUNNING)
        val now = timeProvider.now()
        when (transition) {
            is AutomationWorkTransition.WaitForResource -> {
                session.transitionTo(AutomationWorkStatus.WAITING_RESOURCE)
                session.materialName = transition.resourceName
                session.materialMissing = transition.missingCount
                session.nextCheckAt = reconciliationAt(accountId, now)
                session.lastVerifiedAt = now
            }
            AutomationWorkTransition.WaitForUnknownCooldown -> {
                session.transitionTo(AutomationWorkStatus.WAITING_COOLDOWN)
                session.nextCheckAt = reconciliationAt(accountId, now)
                session.lastVerifiedAt = now
            }
            is AutomationWorkTransition.WaitForConfiguration -> {
                session.transitionTo(AutomationWorkStatus.WAITING_COOLDOWN)
                session.nextCheckAt = reconciliationAt(accountId, now)
                session.holdMessage = transition.message.take(MAX_HOLD_MESSAGE_LENGTH)
                session.lastVerifiedAt = now
            }
            AutomationWorkTransition.Complete -> {
                session.transitionTo(AutomationWorkStatus.COMPLETED)
                session.nextCheckAt = null
                session.holdMessage = null
                session.finishedAt = now
            }
        }
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(
            if (transition == AutomationWorkTransition.Complete) {
                AutomationOwnershipTransferReason.COMPLETE
            } else {
                AutomationOwnershipTransferReason.WAIT
            },
            false,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitForCooldown(accountId: Long, sessionId: Long, nextCheckAt: java.time.Instant) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.status == AutomationWorkStatus.RUNNING)
        session.transitionTo(AutomationWorkStatus.WAITING_COOLDOWN)
        session.nextCheckAt = nextCheckAt
        session.updatedAt = timeProvider.now()
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.WAIT, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitForRaid(
        accountId: Long,
        entryId: Long,
        raidId: String,
        nextCheckAt: java.time.Instant?,
        holdMessage: String?,
    ) {
        requireRunningRuntime(accountId)
        val entry = typed.findEntry(accountId, entryId)
            ?: throw AutomationConfigurationException("Automation entry $entryId is missing.")
        require(entry.type == app.spammy.hof.automation.entity.AutomationType.RAID) {
            "Only a raid entry may create a raid wait session."
        }
        val now = timeProvider.now()
        val open = queries.lockOpen(accountId)
        val session = open.singleOrNull {
            it.entry.id == entryId && it.workType == AutomationWorkType.RAID
        }
        if (session != null) {
            require(session.status in OPEN_SESSION_STATUSES)
            val releasedOwnership = session.status == AutomationWorkStatus.RUNNING
            session.targetKey = raidId
            session.transitionTo(AutomationWorkStatus.WAITING_COOLDOWN)
            session.configVersion = entry.updatedAt.toString()
            session.nextCheckAt = nextCheckAt
            session.holdMessage = holdMessage?.take(MAX_HOLD_MESSAGE_LENGTH)
            session.finishedAt = null
            session.updatedAt = now
            commands.save(session)
            if (releasedOwnership) {
                recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.WAIT, false)
            }
            return
        }
        commands.save(
            AutomationWorkSessionEntity(
                account = entry.account,
                entry = entry,
                workType = AutomationWorkType.RAID,
                targetKey = raidId,
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = entry.updatedAt.toString(),
                nextCheckAt = nextCheckAt,
                holdMessage = holdMessage?.take(MAX_HOLD_MESSAGE_LENGTH),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun triggerRaidConfigurationCheck(accountId: Long) {
        val now = timeProvider.now()
        queries.lockOpen(accountId)
            .filter {
                it.workType == AutomationWorkType.RAID &&
                    it.status == AutomationWorkStatus.WAITING_COOLDOWN
            }
            .forEach { session ->
                session.nextCheckAt = now
                session.updatedAt = now
                commands.save(session)
            }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeBattleMapAction(
        accountId: Long,
        entryId: Long,
        categoryId: String,
        mapCode: String,
        executionIdentity: String?,
    ) {
        if (!isRunningRuntime(accountId, executionIdentity)) return
        val targetKey = "$categoryId/$mapCode"
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.BATTLE_MAP &&
                it.targetKey == targetKey
        } ?: return
        val now = timeProvider.now()
        session.transitionTo(AutomationWorkStatus.COMPLETED)
        session.nextCheckAt = null
        session.holdMessage = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.COMPLETE, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeAdventureAction(
        accountId: Long,
        entryId: Long,
        categoryId: String,
        mapCode: String,
        executionIdentity: String?,
    ) {
        if (!isRunningRuntime(accountId, executionIdentity)) return
        val targetKey = "$categoryId/$mapCode"
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.ADVENTURE_MAP &&
                it.targetKey == targetKey
        } ?: return
        val now = timeProvider.now()
        session.transitionTo(AutomationWorkStatus.COMPLETED)
        session.nextCheckAt = null
        session.holdMessage = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.COMPLETE, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitFishingCycle(
        accountId: Long,
        entryId: Long,
        nextCheckAt: java.time.Instant?,
        holdMessage: String,
    ) {
        requireRunningRuntime(accountId)
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.FISHING &&
                it.targetKey == FISHING_CYCLE_TARGET
        } ?: return
        val now = timeProvider.now()
        session.transitionTo(
            if (nextCheckAt == null) AutomationWorkStatus.WAITING_RESOURCE
            else AutomationWorkStatus.WAITING_COOLDOWN,
        )
        session.nextCheckAt = nextCheckAt
        session.holdMessage = holdMessage.take(MAX_HOLD_MESSAGE_LENGTH)
        session.lastVerifiedAt = now
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.WAIT, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeFishingCycle(accountId: Long, entryId: Long, executionIdentity: String?) {
        if (!isRunningRuntime(accountId, executionIdentity)) return
        // 결과 확인을 위해 작업권을 놓은 낚시도 최신 종료 관측으로 닫는다.
        val session = queries.lockOpen(accountId).singleOrNull {
            it.entry.id == entryId &&
                it.workType == AutomationWorkType.FISHING &&
                it.targetKey == FISHING_CYCLE_TARGET
        } ?: return
        val now = timeProvider.now()
        session.transitionTo(AutomationWorkStatus.COMPLETED)
        session.nextCheckAt = null
        session.holdMessage = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.COMPLETE, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeUnionCycle(accountId: Long, entryId: Long, executionIdentity: String?) {
        if (!isRunningRuntime(accountId, executionIdentity)) return
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.UNION
        } ?: return
        val now = timeProvider.now()
        session.transitionTo(AutomationWorkStatus.COMPLETED)
        session.nextCheckAt = null
        session.holdMessage = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.COMPLETE, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeRaidCycle(accountId: Long, entryId: Long, executionIdentity: String?) {
        if (!isRunningRuntime(accountId, executionIdentity)) return
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.RAID
        } ?: return
        val now = timeProvider.now()
        session.transitionTo(AutomationWorkStatus.COMPLETED)
        session.nextCheckAt = null
        session.holdMessage = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
        recordOwnershipTransferAfterCommit(AutomationOwnershipTransferReason.COMPLETE, false)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun stopForConfigurationChange(
        accountId: Long,
        entryId: Long,
        targetKeys: Set<String>,
        wholeEntry: Boolean,
    ) {
        val now = timeProvider.now()
        queries.lockOpen(accountId)
            .filter { it.entry.id == entryId && (wholeEntry || it.targetKey in targetKeys) }
            .forEach { session ->
                session.transitionTo(AutomationWorkStatus.STOPPED)
                session.nextCheckAt = null
                session.holdMessage = null
                session.finishedAt = now
                session.updatedAt = now
                commands.save(session)
            }
    }

    private fun requireRunningRuntime(accountId: Long) {
        check(isRunningRuntime(accountId)) { "Automation runtime is not running for account $accountId." }
    }

    private fun isRunningRuntime(accountId: Long, executionIdentity: String? = null): Boolean {
        val runtime = typed.lockRuntimeState(accountId)
            ?: throw IllegalStateException("Automation runtime does not exist for account $accountId.")
        if (runtime.lifecycleStatus !in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)) return false
        // 직접 결과는 해당 행동이 아직 작업권을 가진 경우에만 작업을 닫는다.
        // identity 없는 호출은 현재 권위 관측으로 대기 작업을 닫는 기존 경로다.
        if (executionIdentity == null) return true
        val action = typed.findActiveTypedAction(accountId) ?: return false
        return action.executionIdentity == executionIdentity &&
            runtime.leaseToken != null && action.leaseToken == runtime.leaseToken
    }

    private fun requireSession(accountId: Long, sessionId: Long): AutomationWorkSessionEntity =
        queries.lockById(accountId, sessionId)
            ?: throw IllegalArgumentException("Work session $sessionId does not belong to account $accountId.")

    private fun reconciliationAt(accountId: Long, now: java.time.Instant): java.time.Instant {
        val jitterBound = properties.reconciliationJitter.seconds.coerceAtLeast(0)
        val jitterSeconds = if (jitterBound == 0L) 0L else Math.floorMod(accountId, jitterBound + 1)
        return now.plus(properties.reconciliationInterval).plusSeconds(jitterSeconds)
    }

    private fun AutomationWorkSessionEntity.matches(entryId: Long, spec: AutomationWorkAssignment): Boolean =
        entry.id == entryId &&
            workType == spec.type &&
            (targetKey == spec.targetKey || workType in setOf(AutomationWorkType.FISHING, AutomationWorkType.RAID))

    private fun AutomationWorkSessionEntity.alignRaidTarget(
        spec: AutomationWorkAssignment,
        latestConfigVersion: String,
    ): AutomationWorkSessionEntity? {
        if (workType != AutomationWorkType.RAID || targetKey == spec.targetKey) return null
        targetKey = spec.targetKey
        configVersion = latestConfigVersion
        updatedAt = timeProvider.now()
        return this
    }

    private fun releaseOwnership(
        open: List<AutomationWorkSessionEntity>,
        target: AutomationWorkSessionEntity?,
        now: java.time.Instant,
    ): OwnershipTransfer {
        val running = open.filter { it.status == AutomationWorkStatus.RUNNING }
        val previous = running.filter { it !== target && it.id != target?.id }
        if (previous.isNotEmpty()) {
            previous.forEach { session ->
                session.transitionTo(AutomationWorkStatus.YIELDED_PRIORITY)
                session.nextCheckAt = now.plusSeconds(PRIORITY_YIELD_RECHECK_SECONDS)
                session.updatedAt = now
            }
            commands.saveAll(previous)
            commands.flush()
        }
        return OwnershipTransfer(
            previous = previous,
            duplicateRepair = running.size > 1,
            ownershipChanged = previous.isNotEmpty() || target?.status != AutomationWorkStatus.RUNNING,
        )
    }

    private fun resumeAsOwner(session: AutomationWorkSessionEntity, now: java.time.Instant) {
        session.transitionTo(AutomationWorkStatus.RUNNING)
        session.nextCheckAt = null
        session.holdMessage = null
        session.finishedAt = null
        session.updatedAt = now
        commands.save(session)
    }

    private fun logOwnershipTransfer(
        accountId: Long,
        transfer: OwnershipTransfer,
        target: AutomationWorkSessionEntity,
        reason: AutomationOwnershipTransferReason,
    ) {
        if (!transfer.ownershipChanged) return
        val message =
            "Automation work ownership transferred accountId={} previousSessionIds={} targetSessionId={} " +
                "targetEntryId={} targetWorkType={} targetKey={} duplicateRepair={} reason={}"
        val arguments = arrayOf(
            accountId,
            transfer.previous.map { it.id },
            target.id,
            target.entry.id,
            target.workType,
            target.targetKey,
            transfer.duplicateRepair,
            reason,
        )
        recordOwnershipTransferAfterCommit(reason, transfer.duplicateRepair)
        if (transfer.duplicateRepair) {
            log.warn(message, *arguments)
        } else {
            log.info(message, *arguments)
        }
    }

    private data class OwnershipTransfer(
        val previous: List<AutomationWorkSessionEntity>,
        val duplicateRepair: Boolean,
        val ownershipChanged: Boolean,
    )

    private fun recordOwnershipTransferAfterCommit(
        reason: AutomationOwnershipTransferReason,
        duplicateRepair: Boolean,
    ) {
        if (progressTelemetry == null) return
        val safeAction = {
            try {
                progressTelemetry.recordOwnershipTransfer(reason, duplicateRepair)
            } catch (error: RuntimeException) {
                log.warn("Automation ownership telemetry failed after commit reason={}", reason, error)
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = safeAction()
                },
            )
        } else {
            safeAction()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AutomationWorkSessionService::class.java)
        const val MAX_HOLD_MESSAGE_LENGTH = 1000
        const val PRIORITY_YIELD_RECHECK_SECONDS = 10L
        val OPEN_SESSION_STATUSES = setOf(
            AutomationWorkStatus.RUNNING,
            AutomationWorkStatus.WAITING_COOLDOWN,
            AutomationWorkStatus.WAITING_RESOURCE,
            AutomationWorkStatus.YIELDED_PRIORITY,
        )
    }
}
