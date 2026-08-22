package app.spammy.hof.automation.raid

import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.common.time.TimeProvider
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class DefaultRaidCycleModule(
    private val store: RaidCycleStore,
    private val observations: RaidObservationReader,
    private val timeProvider: TimeProvider,
) : RaidCycleModule {
    override fun decideNext(accountId: Long): RaidDirective {
        val state = store.load(accountId)
        val configuration = state.configuration
            ?: return RaidDirective.Hold(
                RaidHoldReason.CONFIGURATION_MISSING,
                "레이드 자동화 설정을 찾을 수 없습니다.",
            )
        if (!configuration.enabled) {
            return RaidDirective.Hold(
                RaidHoldReason.CONFIGURATION_MISSING,
                "레이드 자동화가 비활성화되어 있습니다.",
                entryId = configuration.entryId,
            )
        }
        state.openCycle
            ?.takeIf { cycle -> configuration.targets.none { target -> target.raidId == cycle.raidId } }
            ?.let { cycle ->
                return RaidDirective.Complete(
                    store.finish(
                        accountId = accountId,
                        raidId = cycle.raidId,
                        outcome = RaidCycleOutcomeKind.HANDED_OFF_MANUAL,
                        now = timeProvider.now(),
                    ),
                )
            }
        val ordered = rotate(configuration.targets, configuration.currentTargetKey)
        val target = ordered.firstOrNull()
            ?: return RaidDirective.Hold(
                RaidHoldReason.CONFIGURATION_MISSING,
                "레이드를 하나 이상 선택해 주세요.",
                entryId = configuration.entryId,
            )
        state.openCycle?.battleRecovery?.takeIf { recovery ->
            recovery.nextCheckAt.isAfter(timeProvider.now())
        }?.let { recovery ->
            return RaidDirective.WaitUntil(
                at = recovery.nextCheckAt,
                reason = RaidWaitReason.BATTLE_RECOVERY_RECHECK,
                message = recoveryWarning(recovery, "다음 최신 레이드 상태 확인을 기다립니다."),
                entryId = configuration.entryId,
                raidId = recovery.raidId,
            )
        }
        val observation = observations.read(accountId)
        state.openCycle?.battleRecovery?.takeIf { !observation.fresh }?.let { recovery ->
            val now = timeProvider.now()
            val updated = recovery.copy(
                nextCheckAt = now.plusSeconds(RECOVERY_RECHECK_SECONDS),
                lastObservation = RaidBattleRecoveryObservation.INCOMPLETE,
            )
            store.saveBattleRecovery(accountId, recovery.raidId, updated, now)
            return RaidDirective.Hold(
                RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                recoveryWarning(updated, "이번 조회의 새 관측이 아니어서 전투를 실행하지 않습니다."),
                updated.nextCheckAt,
                configuration.entryId,
                recovery.raidId,
            )
        }
        val joined = observation.raids.filter(RaidObservedTarget::joined)
        if (joined.size > 1) {
            return RaidDirective.Hold(
                RaidHoldReason.UNKNOWN_OR_CONFLICTING_STATE,
                "동시에 여러 레이드 참가 상태가 관측되어 자동 행동을 보류합니다.",
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = target.raidId,
            )
        }
        joined.singleOrNull()
            ?.takeIf { active -> configuration.targets.none { it.raidId == active.id } }
            ?.let { manual ->
                return RaidDirective.Hold(
                    RaidHoldReason.MANUAL_RAID_ACTIVE,
                    "수동 레이드가 끝날 때까지 레이드 자동화만 보류합니다.",
                    recheckAt = timeProvider.now().plusSeconds(
                        (manual.waitSeconds ?: DEFAULT_RECHECK_SECONDS.toInt())
                            .coerceAtLeast(MINIMUM_WAIT_SECONDS)
                            .toLong(),
                    ),
                    entryId = configuration.entryId,
                    raidId = manual.id,
                )
            }
        val activeConfigured = joined.singleOrNull()?.let { active ->
            configuration.targets.singleOrNull { it.raidId == active.id }?.let { it to active }
        }
        val persistedCycle = state.openCycle?.let { open ->
            if (activeConfigured != null && activeConfigured.first.raidId != open.raidId) {
                return RaidDirective.Complete(
                    store.finish(
                        accountId = accountId,
                        raidId = open.raidId,
                        outcome = RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID,
                        now = timeProvider.now(),
                    ),
                )
            } else {
                open
            }
        }
        var cycle = persistedCycle ?: activeConfigured?.let { (activeTarget, active) ->
            store.open(
                accountId = accountId,
                entryId = configuration.entryId,
                target = activeTarget,
                now = timeProvider.now(),
                status = active.toCycleStatus(),
                observedStatus = active.statusText,
            )
        } ?: store.open(
            accountId = accountId,
            entryId = configuration.entryId,
            target = target,
            now = timeProvider.now(),
        )
        val observed = observation.raids.singleOrNull { it.id == cycle.raidId }
            ?: return RaidDirective.Hold(
                RaidHoldReason.TARGET_TEMPORARILY_MISSING,
                "차례인 레이드를 현재 화면에서 확인할 수 없습니다.",
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
            )
        if (observed.status == RaidObservedStatus.CLOSED) {
            return RaidDirective.Complete(
                store.finish(
                    accountId = accountId,
                    raidId = cycle.raidId,
                    outcome = RaidCycleOutcomeKind.ABORTED_CLOSED,
                    now = timeProvider.now(),
                ),
                reasonCode = cycle.battleRecovery?.let { "RAID_BATTLE_RECOVERY_SUPERSEDED" },
                message = cycle.battleRecovery?.let {
                    "레이드가 닫힌 최신 상태가 전투 복구를 대체했습니다."
                },
            )
        }
        if (observed.status in setOf(RaidObservedStatus.TESTING, RaidObservedStatus.UNKNOWN)) {
            return RaidDirective.Hold(
                RaidHoldReason.UNKNOWN_OR_CONFLICTING_STATE,
                "레이드 상태를 안전하게 판단할 수 없어 다시 확인합니다.",
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
            )
        }
        if (cycle.status == RaidAutomationCycleStatus.POST_REWARD_CHECK) {
            if (
                requiresReset(observed) ||
                (RaidIntentKind.REWARD !in observation.globalActions && observation.registrationWait) ||
                (!observed.joined && observed.status in REGISTRATION_STATUSES)
            ) {
                return completeCycle(accountId, cycle)
            }
            if (
                RaidIntentKind.REWARD !in observation.globalActions &&
                RaidIntentKind.REFRESH in observation.globalActions
            ) {
                return RaidDirective.Execute(
                    RaidIntent.Town(
                        entryId = configuration.entryId,
                        raidId = cycle.raidId,
                        raidName = cycle.raidName,
                        kind = RaidIntentKind.REFRESH,
                        requestRaidId = null,
                        observedStatus = observed.statusText,
                    ),
                )
            }
            return RaidDirective.Hold(
                RaidHoldReason.ACTION_UNAVAILABLE,
                "보상 후 레이드 상태와 재등록 대기 정보를 다시 확인합니다.",
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
            )
        }
        if (cycle.status == RaidAutomationCycleStatus.REGISTERED_WAITING) {
            if (!observed.joined && observed.status in REGISTRATION_STATUSES) {
                return RaidDirective.Complete(
                    store.finish(
                        accountId = accountId,
                        raidId = cycle.raidId,
                        outcome = RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST,
                        now = timeProvider.now(),
                    ),
                )
            }
        }
        if (
            cycle.status == RaidAutomationCycleStatus.IN_BATTLE &&
            observed.status == RaidObservedStatus.IN_BATTLE &&
            !observed.joined
        ) {
            val recoveryWasSuperseded = cycle.battleRecovery != null
            return RaidDirective.Complete(
                store.finish(
                    accountId = accountId,
                    raidId = cycle.raidId,
                    outcome = RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST,
                    now = timeProvider.now(),
                ),
                reasonCode = if (recoveryWasSuperseded) "RAID_BATTLE_RECOVERY_SUPERSEDED" else null,
                message = if (recoveryWasSuperseded) {
                    "최신 레이드 상태에서 참가 해제가 확인되어 전투 복구를 종료했습니다."
                } else {
                    null
                },
            )
        }
        if (
            cycle.status == RaidAutomationCycleStatus.IN_BATTLE &&
            !observed.joined &&
            observed.status in REGISTRATION_STATUSES
        ) {
            return completeCycle(accountId, cycle)
        }
        if (
            cycle.status == RaidAutomationCycleStatus.REWARD_PENDING &&
            observed.status != RaidObservedStatus.COMPLETED
        ) {
            return completeCycle(accountId, cycle)
        }
        if (
            cycle.status != RaidAutomationCycleStatus.PREPARING &&
            requiresReset(observed)
        ) {
            return completeCycle(accountId, cycle)
        }
        val observedPhase = when {
            observed.status == RaidObservedStatus.IN_BATTLE &&
                cycle.status in setOf(
                    RaidAutomationCycleStatus.PREPARING,
                    RaidAutomationCycleStatus.REGISTERED_WAITING,
                ) -> RaidAutomationCycleStatus.IN_BATTLE
            observed.status == RaidObservedStatus.COMPLETED &&
                !requiresReset(observed) &&
                cycle.status in setOf(
                    RaidAutomationCycleStatus.PREPARING,
                    RaidAutomationCycleStatus.REGISTERED_WAITING,
                    RaidAutomationCycleStatus.IN_BATTLE,
                ) -> RaidAutomationCycleStatus.REWARD_PENDING
            observed.joined &&
                observed.status in REGISTRATION_STATUSES &&
                cycle.status == RaidAutomationCycleStatus.PREPARING -> RaidAutomationCycleStatus.REGISTERED_WAITING
            else -> null
        }
        val recoveryAppliedByCompleted =
            cycle.battleRecovery != null && observedPhase == RaidAutomationCycleStatus.REWARD_PENDING
        if (observedPhase != null && observedPhase != cycle.status) {
            if (recoveryAppliedByCompleted) {
                cycle = store.clearBattleRecovery(accountId, cycle.raidId, timeProvider.now())
            }
            cycle = store.transition(
                accountId = accountId,
                raidId = cycle.raidId,
                status = observedPhase,
                observedStatus = observed.statusText,
                nextCheckAt = null,
                now = timeProvider.now(),
            )
        }
        if (cycle.status == RaidAutomationCycleStatus.REGISTERED_WAITING) {
            observed.waitSeconds?.takeIf { it > 0 }?.let { seconds ->
                return RaidDirective.WaitUntil(
                    at = timeProvider.now().plusSeconds(seconds.coerceAtLeast(MINIMUM_WAIT_SECONDS).toLong()),
                    reason = RaidWaitReason.WAITING_TO_START,
                    message = "레이드 출발 가능 시각까지 기다립니다.",
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                )
            }
            if (observed.joined && observed.status == RaidObservedStatus.READY && RaidIntentKind.START in observed.actions) {
                return RaidDirective.Execute(
                    RaidIntent.Town(
                        entryId = configuration.entryId,
                        raidId = cycle.raidId,
                        raidName = cycle.raidName,
                        kind = RaidIntentKind.START,
                        observedStatus = observed.statusText,
                    ),
                )
            }
            return RaidDirective.Hold(
                RaidHoldReason.ACTION_UNAVAILABLE,
                "등록한 레이드의 출발 가능 상태를 다시 확인합니다.",
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
            )
        }
        if (cycle.status == RaidAutomationCycleStatus.REWARD_PENDING) {
            if (RaidIntentKind.REWARD in observation.globalActions) {
                return RaidDirective.Execute(
                    RaidIntent.Town(
                        entryId = configuration.entryId,
                        raidId = cycle.raidId,
                        raidName = cycle.raidName,
                        kind = RaidIntentKind.REWARD,
                        requestRaidId = null,
                        observedStatus = observed.statusText,
                    ),
                    reasonCode = if (recoveryAppliedByCompleted) "RAID_BATTLE_APPLIED_COMPLETED" else null,
                    message = if (recoveryAppliedByCompleted) {
                        "완료·보상 단계 진입으로 이전 레이드 전투 적용을 확인했습니다."
                    } else {
                        null
                    },
                )
            }
            return RaidDirective.Hold(
                RaidHoldReason.ACTION_UNAVAILABLE,
                if (recoveryAppliedByCompleted) {
                    "완료 단계 진입으로 이전 레이드 전투 적용을 확인했습니다. 레이드 보상 동작을 다시 확인합니다."
                } else {
                    "레이드 보상 동작을 다시 확인합니다."
                },
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                reasonCode = if (recoveryAppliedByCompleted) "RAID_BATTLE_APPLIED_COMPLETED" else null,
            )
        }
        if (cycle.status == RaidAutomationCycleStatus.IN_BATTLE && observed.status == RaidObservedStatus.IN_BATTLE) {
            cycle.battleRecovery?.let { recovery ->
                return decideBattleRecovery(accountId, configuration, cycle, observed, recovery)
            }
            if (observed.battleAvailability == RaidBattleAvailability.INCOMPLETE) {
                return RaidDirective.Hold(
                    RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                    "레이드 전투 맵 관측이 불완전해 실행하지 않고 다시 확인합니다.",
                    recheckAt = timeProvider.now().plusSeconds(RECOVERY_RECHECK_SECONDS),
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                )
            }
            if (observed.battleAvailability == RaidBattleAvailability.ABSENT) {
                return RaidDirective.Hold(
                    RaidHoldReason.BATTLE_TARGET_ABSENT,
                    "최신 레이드 화면에서 전투 맵이 없어 실행하지 않고 다시 확인합니다.",
                    recheckAt = timeProvider.now().plusSeconds(RECOVERY_RECHECK_SECONDS),
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                )
            }
            val battle = observed.battle
                ?: return RaidDirective.Hold(
                    RaidHoldReason.TARGET_TEMPORARILY_MISSING,
                    "레이드 전투 대상을 다시 확인합니다.",
                    recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                )
            battle.cooldownRemainingSeconds?.takeIf { it > 0 }?.let { seconds ->
                return RaidDirective.WaitUntil(
                    at = timeProvider.now().plusSeconds(seconds),
                    reason = RaidWaitReason.BATTLE_COOLDOWN,
                    message = "다음 레이드 전투 가능 시각까지 기다립니다.",
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                )
            }
            return battleDirective(accountId, configuration, cycle, battle)
        }
        if (cycle.status != RaidAutomationCycleStatus.PREPARING) {
            return RaidDirective.Hold(
                RaidHoldReason.ACTION_UNAVAILABLE,
                "진행 중인 레이드의 다음 단계를 다시 확인합니다.",
                recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
            )
        }
        if (requiresReset(observed)) {
            if (RaidIntentKind.RESET !in observed.actions) {
                return RaidDirective.Hold(
                    RaidHoldReason.ACTION_UNAVAILABLE,
                    "초기화가 필요한 레이드의 RESET 동작을 확인할 수 없습니다.",
                    recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                )
            }
            return RaidDirective.Execute(
                RaidIntent.Town(
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                    raidName = cycle.raidName,
                    kind = RaidIntentKind.RESET,
                    observedStatus = observed.statusText,
                ),
            )
        }
        if (observation.registrationWait) {
            return RaidDirective.WaitUntil(
                at = timeProvider.now().plusSeconds(
                    (observation.registrationWaitSeconds ?: DEFAULT_RECHECK_SECONDS.toInt())
                        .coerceAtLeast(MINIMUM_WAIT_SECONDS)
                        .toLong(),
                ),
                reason = RaidWaitReason.REGISTRATION_COOLDOWN,
                message = "레이드 등록 쿨타임을 기다립니다.",
                entryId = configuration.entryId,
                raidId = cycle.raidId,
            )
        }
        if (
            observed.playable &&
            !observed.joined &&
            observed.status in REGISTRATION_STATUSES &&
            RaidIntentKind.REGISTER in observed.actions
        ) {
            return RaidDirective.Execute(
                RaidIntent.Town(
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                    raidName = cycle.raidName,
                    kind = RaidIntentKind.REGISTER,
                    observedStatus = observed.statusText,
                ),
            )
        }
        return RaidDirective.Hold(
            RaidHoldReason.ACTION_UNAVAILABLE,
            "현재 레이드에서 실행할 다음 동작을 확인할 수 없습니다.",
            recheckAt = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
            entryId = configuration.entryId,
            raidId = cycle.raidId,
        )
    }

    override fun recordObservedResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
    ): RaidRecordResult {
        if (observation == RaidResultObservation.ManualStop) {
            val cycle = store.load(accountId).openCycle ?: return RaidRecordResult.Recorded()
            if (cycle.battleRecovery != null) {
                store.clearBattleRecovery(accountId, cycle.raidId, timeProvider.now())
            }
            return RaidRecordResult.Recorded()
        }
        if (observation == RaidResultObservation.ManualHandoff) {
            val cycle = store.load(accountId).openCycle
                ?: return RaidRecordResult.Recorded()
            if (cycle.raidId != attempt.raidId) {
                return needsRecheck("수동으로 인계할 레이드와 열린 사이클의 대상이 일치하지 않습니다.")
            }
            return RaidRecordResult.Recorded(
                store.finish(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    outcome = RaidCycleOutcomeKind.HANDED_OFF_MANUAL,
                    now = timeProvider.now(),
                ),
            )
        }
        if (observation is RaidResultObservation.LegacyCycleAbort) {
            val cycle = store.load(accountId).openCycle
                ?: return RaidRecordResult.Recorded()
            if (cycle.raidId != attempt.raidId) {
                return needsRecheck("정리할 레이드 사이클과 저장 행동의 대상이 일치하지 않습니다.")
            }
            return RaidRecordResult.Recorded(
                store.finish(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    outcome = observation.reason,
                    now = timeProvider.now(),
                ),
            )
        }
        if (observation == RaidResultObservation.BattleCompleted) {
            store.load(accountId).openCycle
                ?.takeIf { cycle -> cycle.raidId == attempt.raidId && cycle.battleRecovery != null }
                ?.let { cycle -> store.clearBattleRecovery(accountId, cycle.raidId, timeProvider.now()) }
            return RaidRecordResult.Recorded()
        }
        if (observation is RaidResultObservation.BattleAmbiguous) {
            return recordAmbiguousBattle(accountId, attempt, observation)
        }
        val page = (observation as? RaidResultObservation.Page)?.value
            ?: return needsRecheck("레이드 화면 관측 결과가 필요합니다.")
        val cycle = store.load(accountId).openCycle
            ?: return needsRecheck("확정할 열린 레이드 사이클이 없습니다.")
        if (cycle.raidId != attempt.raidId) {
            return needsRecheck("실행 대상과 열린 레이드 사이클이 일치하지 않습니다.")
        }
        if (attempt.kind == RaidIntentKind.RESET) {
            val target = page.raids.singleOrNull { it.id == attempt.raidId }
            if (
                target != null &&
                !target.joined &&
                target.status in REGISTRATION_STATUSES &&
                !requiresReset(target)
            ) {
                store.transition(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    status = RaidAutomationCycleStatus.PREPARING,
                    observedStatus = target.statusText,
                    nextCheckAt = null,
                    now = timeProvider.now(),
                )
                return RaidRecordResult.Recorded()
            }
        }
        if (attempt.kind == RaidIntentKind.REGISTER) {
            val target = page.raids.singleOrNull { it.id == attempt.raidId }
            if (target?.joined == true) {
                val nextCheckAt = target.waitSeconds
                    ?.takeIf { it > 0 }
                    ?.let { timeProvider.now().plusSeconds(it.coerceAtLeast(MINIMUM_WAIT_SECONDS).toLong()) }
                store.transition(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    status = target.toCycleStatus(),
                    observedStatus = target.statusText,
                    nextCheckAt = nextCheckAt.takeIf {
                        target.status in REGISTRATION_STATUSES
                    },
                    now = timeProvider.now(),
                )
                return RaidRecordResult.Recorded()
            }
        }
        if (attempt.kind == RaidIntentKind.START) {
            val target = page.raids.singleOrNull { it.id == attempt.raidId }
            if (target?.status in setOf(RaidObservedStatus.IN_BATTLE, RaidObservedStatus.COMPLETED)) {
                store.transition(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    status = requireNotNull(target).toCycleStatus(),
                    observedStatus = target.statusText,
                    nextCheckAt = null,
                    now = timeProvider.now(),
                )
                return RaidRecordResult.Recorded()
            }
        }
        if (attempt.kind == RaidIntentKind.REWARD && rewardResultIsProven(page, attempt.raidId)) {
            store.transition(
                accountId = accountId,
                raidId = attempt.raidId,
                status = RaidAutomationCycleStatus.POST_REWARD_CHECK,
                observedStatus = page.raids.singleOrNull { it.id == attempt.raidId }?.statusText,
                nextCheckAt = null,
                now = timeProvider.now(),
            )
            if (page.registrationWait) {
                val completion = store.finish(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    outcome = RaidCycleOutcomeKind.COMPLETED,
                    now = timeProvider.now(),
                    advanceRotation = true,
                )
                return RaidRecordResult.Recorded(completion)
            }
            return RaidRecordResult.Recorded()
        }
        if (attempt.kind == RaidIntentKind.REFRESH && postRewardStateIsProven(page, attempt.raidId)) {
            return RaidRecordResult.Recorded(
                store.finish(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    outcome = RaidCycleOutcomeKind.COMPLETED,
                    now = timeProvider.now(),
                    advanceRotation = true,
                ),
            )
        }
        if (actionIsProvablyNotApplied(page, attempt)) {
            return RaidRecordResult.NotApplied("권위 상태에서 이전 레이드 요청이 적용되지 않은 것을 확인했습니다.")
        }
        return needsRecheck("레이드 실행 결과가 아직 적용을 증명하지 못했습니다.")
    }

    private fun completeCycle(accountId: Long, cycle: RaidCycleSnapshot): RaidDirective.Complete {
        val recoveryWasSuperseded = cycle.battleRecovery != null
        return RaidDirective.Complete(
            store.finish(
            accountId = accountId,
            raidId = cycle.raidId,
            outcome = RaidCycleOutcomeKind.COMPLETED,
            now = timeProvider.now(),
            advanceRotation = true,
            ),
            reasonCode = if (recoveryWasSuperseded) "RAID_BATTLE_RECOVERY_SUPERSEDED" else null,
            message = if (recoveryWasSuperseded) {
                "초기화·등록 대기 등 최신 레이드 상태가 전투 복구를 대체했습니다."
            } else {
                null
            },
        )
    }

    private fun recordAmbiguousBattle(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation.BattleAmbiguous,
    ): RaidRecordResult {
        if (attempt.kind != RaidIntentKind.BATTLE) {
            return needsRecheck("레이드 전투가 아닌 행동은 전투 복구로 넘길 수 없습니다.")
        }
        val cycle = store.load(accountId).openCycle
            ?: return needsRecheck("복구할 열린 레이드 사이클이 없습니다.")
        if (cycle.raidId != attempt.raidId || cycle.status != RaidAutomationCycleStatus.IN_BATTLE) {
            return needsRecheck("불명확한 전투와 열린 레이드 사이클이 일치하지 않습니다.")
        }
        val executionIdentity = attempt.executionIdentity
            ?: return needsRecheck("불명확한 레이드 전투의 실행 식별자가 없습니다.")
        val categoryId = attempt.categoryId
            ?: return needsRecheck("불명확한 레이드 전투의 카테고리가 없습니다.")
        val mapCode = attempt.mapCode
            ?: return needsRecheck("불명확한 레이드 전투의 맵이 없습니다.")
        val existing = cycle.battleRecovery
        if (existing != null && attempt.recoveryChainId != existing.chainId) {
            return needsRecheck("불명확한 레이드 전투의 복구 연결 식별자가 일치하지 않습니다.")
        }
        val now = timeProvider.now()
        val submittedAt = attempt.submittedAt ?: now
        val recovery = RaidBattleRecovery(
            chainId = existing?.chainId ?: attempt.recoveryChainId ?: UUID.randomUUID().toString(),
            raidId = attempt.raidId,
            categoryId = categoryId,
            mapCode = mapCode,
            originalExecutionIdentity = existing?.originalExecutionIdentity ?: executionIdentity,
            latestExecutionIdentity = executionIdentity,
            firstAmbiguousAt = existing?.firstAmbiguousAt ?: now,
            lastSubmittedAt = submittedAt,
            retransmissionCount = maxOf(existing?.retransmissionCount ?: 0, attempt.retransmissionCount),
            nextCheckAt = submittedAt.plusSeconds(RECOVERY_RECHECK_SECONDS),
            submittedFromRunnable = existing?.submittedFromRunnable == true || attempt.submittedFromRunnable,
            lastObservation = RaidBattleRecoveryObservation.RESULT_UNOBSERVED,
        )
        store.saveBattleRecovery(accountId, attempt.raidId, recovery, now)
        return RaidRecordResult.BattleRecoveryStarted(
            recovery.nextCheckAt,
            recoveryWarning(recovery, observation.reason),
        )
    }

    private fun decideBattleRecovery(
        accountId: Long,
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
        observed: RaidObservedTarget,
        recovery: RaidBattleRecovery,
    ): RaidDirective {
        val now = timeProvider.now()
        if (observed.battleAvailability == RaidBattleAvailability.INCOMPLETE) {
            val updated = recovery.copy(
                nextCheckAt = now.plusSeconds(RECOVERY_RECHECK_SECONDS),
                lastObservation = RaidBattleRecoveryObservation.INCOMPLETE,
            )
            store.saveBattleRecovery(accountId, cycle.raidId, updated, now)
            return RaidDirective.Hold(
                RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                recoveryWarning(updated, "레이드 전투 맵을 완전하게 관측하지 못했습니다."),
                updated.nextCheckAt,
                configuration.entryId,
                cycle.raidId,
            )
        }
        if (observed.battleAvailability == RaidBattleAvailability.ABSENT) {
            val updated = recovery.copy(
                nextCheckAt = now.plusSeconds(RECOVERY_RECHECK_SECONDS),
                lastObservation = RaidBattleRecoveryObservation.ABSENT,
            )
            store.saveBattleRecovery(accountId, cycle.raidId, updated, now)
            return RaidDirective.Hold(
                RaidHoldReason.BATTLE_TARGET_ABSENT,
                recoveryWarning(updated, "최신 레이드 화면에서 전투 맵이 보이지 않습니다."),
                updated.nextCheckAt,
                configuration.entryId,
                cycle.raidId,
            )
        }
        val battle = observed.battle ?: run {
            val updated = recovery.copy(
                nextCheckAt = now.plusSeconds(RECOVERY_RECHECK_SECONDS),
                lastObservation = RaidBattleRecoveryObservation.INCOMPLETE,
            )
            store.saveBattleRecovery(accountId, cycle.raidId, updated, now)
            return RaidDirective.Hold(
                RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                recoveryWarning(updated, "레이드 전투 맵 세부 정보를 확인하지 못했습니다."),
                updated.nextCheckAt,
                configuration.entryId,
                cycle.raidId,
            )
        }
        if (observed.battleAvailability == RaidBattleAvailability.COOLDOWN) {
            val applied =
                recovery.submittedFromRunnable &&
                    battle.categoryId == recovery.categoryId &&
                    battle.mapCode == recovery.mapCode
            if (applied) {
                store.clearBattleRecovery(accountId, cycle.raidId, now)
            } else {
                store.saveBattleRecovery(
                    accountId,
                    cycle.raidId,
                    recovery.copy(lastObservation = RaidBattleRecoveryObservation.COOLDOWN),
                    now,
                )
            }
            val seconds = battle.cooldownRemainingSeconds?.takeIf { it > 0 } ?: RECOVERY_RECHECK_SECONDS
            return RaidDirective.WaitUntil(
                now.plusSeconds(seconds),
                if (applied) RaidWaitReason.BATTLE_APPLIED_COOLDOWN else RaidWaitReason.BATTLE_COOLDOWN,
                if (applied) {
                    "새 쿨타임으로 이전 레이드 전투 적용을 확인했습니다. 다음 전투 가능 시각까지 기다립니다."
                } else {
                    recoveryWarning(recovery, "제출 전 관측이 없어 현재 쿨타임만으로 적용을 추정하지 않습니다.")
                },
                configuration.entryId,
                cycle.raidId,
            )
        }
        if (battle.categoryId != recovery.categoryId || battle.mapCode != recovery.mapCode) {
            store.clearBattleRecovery(accountId, cycle.raidId, now)
            return battleDirective(
                accountId,
                configuration,
                cycle,
                battle,
                recoverySupersededByMap = true,
            )
        }
        return battleDirective(accountId, configuration, cycle, battle, recovery)
    }

    private fun battleDirective(
        accountId: Long,
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
        battle: RaidObservedBattle,
        recovery: RaidBattleRecovery? = null,
        recoverySupersededByMap: Boolean = false,
    ): RaidDirective {
        val now = timeProvider.now()
        val setting = configuration.targets.singleOrNull { it.raidId == cycle.raidId }
            ?: return RaidDirective.Complete(
                store.finish(accountId, cycle.raidId, RaidCycleOutcomeKind.HANDED_OFF_MANUAL, now),
            )
        val presetId = setting.presetId
            ?: return RaidDirective.Hold(
                RaidHoldReason.INVALID_PRESET,
                when {
                    recovery != null -> recoveryWarning(recovery, "최신 레이드 전투 프리셋을 선택해 주세요.")
                    recoverySupersededByMap -> "다른 전투 맵 관측으로 이전 복구를 종료했습니다. 최신 레이드 전투 프리셋을 선택해 주세요."
                    else -> "레이드 전투 프리셋을 선택해 주세요."
                },
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                reasonCode = if (recoverySupersededByMap) "RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP" else null,
            )
        val party = setting.party
            ?: return RaidDirective.Hold(
                RaidHoldReason.INVALID_PRESET,
                when {
                    recovery != null -> recoveryWarning(recovery, "최신 레이드 전투 프리셋 구성을 확인해 주세요.")
                    recoverySupersededByMap -> "다른 전투 맵 관측으로 이전 복구를 종료했습니다. 최신 레이드 전투 프리셋 구성을 확인해 주세요."
                    else -> "레이드 전투 프리셋 구성을 확인해 주세요."
                },
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                reasonCode = if (recoverySupersededByMap) "RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP" else null,
            )
        val retransmission = recovery?.retransmissionCount?.plus(1) ?: 0
        val intent = RaidIntent.Battle(
            entryId = configuration.entryId,
            raidId = cycle.raidId,
            raidName = cycle.raidName,
            categoryId = battle.categoryId,
            mapCode = battle.mapCode,
            presetMode = setting.presetMode,
            presetId = presetId,
            party = party,
            recoveryChainId = recovery?.chainId,
            retransmissionCount = retransmission,
            submittedFromRunnable = true,
        )
        if (recovery == null) {
            return RaidDirective.Execute(
                intent,
                reasonCode = if (recoverySupersededByMap) "RAID_BATTLE_RECOVERY_SUPERSEDED_BY_MAP" else null,
                message = if (recoverySupersededByMap) {
                    "최신 관측에서 다른 전투 맵으로 바뀌어 이전 복구를 종료하고 새 맵을 실행합니다."
                } else {
                    null
                },
            )
        }
        store.saveBattleRecovery(
            accountId,
            cycle.raidId,
            recovery.copy(lastObservation = RaidBattleRecoveryObservation.RUNNABLE),
            now,
        )
        val pending = recovery.copy(
            lastSubmittedAt = now,
            retransmissionCount = retransmission,
            nextCheckAt = now.plusSeconds(RECOVERY_RECHECK_SECONDS),
            lastObservation = RaidBattleRecoveryObservation.RESULT_UNOBSERVED,
        )
        return RaidDirective.Execute(
            intent,
            reasonCode = "RAID_BATTLE_RETRANSMIT",
            message = "완전한 최신 GET과 최신 설정 확인 뒤 레이드 전투를 재전송합니다.",
            warning = recoveryWarning(pending, "레이드 전투 재전송 결과를 기다립니다."),
        )
    }

    private fun recoveryWarning(recovery: RaidBattleRecovery, detail: String): String =
        recovery.warningMessage(detail)

    private fun rewardResultIsProven(page: RaidObservation, raidId: String): Boolean {
        if (RaidIntentKind.REWARD in page.globalActions) return false
        val target = page.raids.singleOrNull { it.id == raidId }
        return page.applied ||
            page.registrationWait ||
            target?.let(::requiresReset) == true ||
            (target != null && !target.joined && target.status in REGISTRATION_STATUSES)
    }

    private fun postRewardStateIsProven(page: RaidObservation, raidId: String): Boolean {
        if (RaidIntentKind.REWARD in page.globalActions) return false
        val target = page.raids.singleOrNull { it.id == raidId }
        return page.registrationWait ||
            target?.let(::requiresReset) == true ||
            (target != null && !target.joined && target.status in REGISTRATION_STATUSES)
    }

    private fun actionIsProvablyNotApplied(page: RaidObservation, attempt: RaidAttempt): Boolean {
        if (page.applied) return false
        val target = page.raids.singleOrNull { it.id == attempt.raidId }
        return when (attempt.kind) {
            RaidIntentKind.RESET -> target != null &&
                !target.joined &&
                requiresReset(target) &&
                RaidIntentKind.RESET in target.actions
            RaidIntentKind.REGISTER -> target != null &&
                !target.joined &&
                target.status in REGISTRATION_STATUSES &&
                !page.registrationWait &&
                RaidIntentKind.REGISTER in target.actions
            RaidIntentKind.START -> target?.joined == true &&
                target.status == RaidObservedStatus.READY &&
                RaidIntentKind.START in target.actions
            RaidIntentKind.REWARD -> RaidIntentKind.REWARD in page.globalActions
            RaidIntentKind.REFRESH -> RaidIntentKind.REFRESH in page.globalActions &&
                !postRewardStateIsProven(page, attempt.raidId)
            RaidIntentKind.BATTLE -> false
        }
    }

    private fun needsRecheck(message: String) = RaidRecordResult.NeedsRecheck(
        timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
        message,
    )

    private fun rotate(targets: List<RaidCycleTarget>, currentTargetKey: String?): List<RaidCycleTarget> {
        val ordered = targets.sortedWith(compareBy(RaidCycleTarget::executionOrder, RaidCycleTarget::raidId))
        val index = ordered.indexOfFirst { it.raidId == currentTargetKey }
        return if (index <= 0) ordered else ordered.drop(index) + ordered.take(index)
    }

    private fun requiresReset(target: RaidObservedTarget): Boolean =
        target.statusText?.let(RESET_REQUIRED_STATUS::containsMatchIn) == true

    private fun RaidObservedTarget.toCycleStatus(): RaidAutomationCycleStatus = when (status) {
        RaidObservedStatus.IN_BATTLE -> RaidAutomationCycleStatus.IN_BATTLE
        RaidObservedStatus.COMPLETED -> RaidAutomationCycleStatus.REWARD_PENDING
        else -> RaidAutomationCycleStatus.REGISTERED_WAITING
    }

    private companion object {
        const val DEFAULT_RECHECK_SECONDS = 30L
        const val RECOVERY_RECHECK_SECONDS = 300L
        const val MINIMUM_WAIT_SECONDS = 5
        val RESET_REQUIRED_STATUS = Regex("보상\\s*확인\\s*종료\\s*\\(\\s*리셋\\s*가능\\s*\\)")
        val REGISTRATION_STATUSES = setOf(
            RaidObservedStatus.RECRUITING,
            RaidObservedStatus.WAITING,
            RaidObservedStatus.READY,
        )
    }
}
