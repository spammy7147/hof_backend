package app.spammy.hof.automation.raid

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.automation.entity.RaidAutomationCycleStatus
import app.spammy.hof.automation.config.RaidAutomationProperties
import app.spammy.hof.automation.service.AutomationDiagnosticKind
import app.spammy.hof.automation.service.AutomationImpactScope
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.raid.model.RaidRegistrationResultEvidence
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class DefaultRaidCycleModule(
    private val store: RaidCycleStore,
    private val observations: RaidObservationReader,
    private val timeProvider: TimeProvider,
    private val properties: RaidAutomationProperties = RaidAutomationProperties(),
    private val convergence: AutomationActionConvergenceModule? = null,
) : RaidCycleModule {
    override fun decide(accountId: Long): RaidDecision {
        val state = store.load(accountId)
        val configuration = state.configuration
            ?: return RaidDirective.Hold(
                RaidHoldReason.CONFIGURATION_MISSING,
                "레이드 자동화 설정을 찾을 수 없습니다.",
            ).asDecision()
        if (!configuration.enabled) {
            return RaidDirective.Hold(
                RaidHoldReason.CONFIGURATION_MISSING,
                "레이드 자동화가 비활성화되어 있습니다.",
                entryId = configuration.entryId,
            ).asDecision()
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
                ).asDecision()
            }
        val ordered = rotate(configuration.targets, configuration.currentTargetKey)
        val target = ordered.firstOrNull()
            ?: return RaidDirective.Hold(
                RaidHoldReason.CONFIGURATION_MISSING,
                "레이드를 하나 이상 선택해 주세요.",
                entryId = configuration.entryId,
            ).asDecision()
        if (state.openCycle == null) {
            return RaidDirective.Execute(
                RaidIntent.Town(
                    entryId = configuration.entryId,
                    raidId = target.raidId,
                    raidName = target.name,
                    kind = RaidIntentKind.REFRESH,
                    requestRaidId = null,
                ),
                reasonCode = "RAID_INITIAL_STATUS_REFRESH",
                message = "레이드 행동 전에 최신 상태를 갱신합니다.",
            ).asDecision()
        }
        state.openCycle.takeIf { cycle ->
            cycle.status == RaidAutomationCycleStatus.REWARD_PENDING &&
                cycle.nextCheckAt?.isAfter(timeProvider.now()) == true
        }?.let { cycle ->
            return RaidDirective.WaitUntil(
                at = requireNotNull(cycle.nextCheckAt),
                reason = RaidWaitReason.POST_REWARD_CHECK,
                message = "레이드 보상 요청 거절 뒤 최신 보상 상태 재확인 시각까지 기다립니다.",
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "마감 뒤 최신 보상 가능 상태 재확인",
                reasonCode = "RAID_REWARD_REJECTION_RECHECK",
            ).asDecision()
        }
        state.openCycle.takeIf { cycle ->
            cycle.status == RaidAutomationCycleStatus.REGISTRATION_COOLDOWN &&
                cycle.nextCheckAt?.isAfter(timeProvider.now()) == true
        }?.let { cycle ->
            return RaidDirective.WaitUntil(
                at = requireNotNull(cycle.nextCheckAt),
                reason = RaidWaitReason.REGISTRATION_COOLDOWN,
                message = "HOF가 확인한 레이드 등록 가능 시각까지 이 레이드 범위만 기다립니다.",
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "등록 대기 종료 뒤 상태 갱신과 최신 레이드 상태 재확인",
                reasonCode = "RAID_REGISTRATION_COOLDOWN",
            ).asDecision()
        }
        state.openCycle.takeIf { cycle ->
            cycle.nextCheckAt?.isAfter(timeProvider.now()) == false
        }?.let { cycle ->
            return RaidDirective.Execute(
                RaidIntent.Town(
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                    raidName = cycle.raidName,
                    kind = RaidIntentKind.REFRESH,
                    requestRaidId = null,
                ),
                reasonCode = "RAID_DUE_STATUS_REFRESH",
                message = "레이드 재확인 시각이 되어 최신 상태를 먼저 갱신합니다.",
            ).asDecision()
        }
        state.openCycle.takeIf { cycle ->
            cycle.status == RaidAutomationCycleStatus.POST_REWARD_CHECK
        }?.let { cycle ->
            return RaidDirective.Execute(
                RaidIntent.Town(
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                    raidName = cycle.raidName,
                    kind = RaidIntentKind.REFRESH,
                    requestRaidId = null,
                ),
                reasonCode = "RAID_POST_REWARD_STATUS_REFRESH",
                message = "보상 수령을 확인한 뒤 레이드 쿨타임 상태를 갱신합니다.",
            ).asDecision()
        }
        state.openCycle.battleRecovery?.takeIf { recovery ->
            recovery.nextCheckAt.isAfter(timeProvider.now())
        }?.let { recovery ->
            return RaidDirective.WaitUntil(
                at = recovery.nextCheckAt,
                reason = RaidWaitReason.BATTLE_RECOVERY_RECHECK,
                message = recoveryWarning(recovery, "다음 최신 레이드 상태 확인을 기다립니다."),
                entryId = configuration.entryId,
                raidId = recovery.raidId,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 레이드 상태에서 전투 결과 재확인",
            ).asDecision()
        }
        val observation = observations.read(accountId)
        val directive = decideFromObservation(accountId, state, configuration, target, observation)
        val authoritativeState = observation
            .takeIf(RaidObservation::fresh)
            ?.let { directive.resolveAuthoritativeState(it) }
        return RaidDecision(directive, authoritativeState)
    }

    private fun decideFromObservation(
        accountId: Long,
        state: RaidCycleAccountState,
        configuration: RaidCycleConfiguration,
        target: RaidCycleTarget,
        observation: RaidObservation,
    ): RaidDirective {
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
                diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 레이드 전투 가능 상태 재관측",
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
                diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "단일 참가 레이드가 확인되면 새 판단",
            )
        }
        joined.singleOrNull()
            ?.takeIf { active -> configuration.targets.none { it.raidId == active.id } }
            ?.let { manual ->
                return RaidDirective.Hold(
                    RaidHoldReason.MANUAL_RAID_ACTIVE,
                    "수동 레이드가 끝날 때까지 레이드 자동화만 보류합니다.",
                    recheckAt = timeProvider.now().plusSeconds(MANUAL_RAID_RECHECK_SECONDS),
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
        if (
            !observed.joined &&
            (
                observed.status == RaidObservedStatus.IN_BATTLE ||
                    (observed.status == RaidObservedStatus.COMPLETED && !requiresReset(observed))
            )
        ) {
            val recoveryWasSuperseded = cycle.battleRecovery != null
            return RaidDirective.Complete(
                store.finish(
                    accountId = accountId,
                    raidId = cycle.raidId,
                    outcome = RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST,
                    now = timeProvider.now(),
                ),
                reasonCode = if (recoveryWasSuperseded) {
                    "RAID_BATTLE_RECOVERY_SUPERSEDED"
                } else {
                    "RAID_EXTERNAL_OWNERSHIP_OBSERVED"
                },
                message = if (recoveryWasSuperseded) {
                    "참가 해제 최신 상태가 진행 중이던 전투 복구를 대체했습니다."
                } else {
                    "현재 계정이 참가하지 않은 외부 레이드 단계라 자동 행동 없이 사이클을 종료합니다."
                },
            )
        }
        if (cycle.status in REGISTRATION_RECOVERY_STATUSES) {
            if (RaidIntentKind.REFRESH in observation.globalActions) {
                return RaidDirective.Execute(
                    RaidIntent.Town(
                        entryId = configuration.entryId,
                        raidId = cycle.raidId,
                        raidName = cycle.raidName,
                        kind = RaidIntentKind.REFRESH,
                        requestRaidId = null,
                        observedStatus = observed.statusText,
                    ),
                    reasonCode = "RAID_REGISTRATION_STATUS_REFRESH",
                    message = "기존 전투 또는 등록 대기 상태를 HOF에서 갱신합니다.",
                )
            }
            return RaidDirective.Hold(
                RaidHoldReason.ACTION_UNAVAILABLE,
                "레이드 등록 상태 갱신 동작을 다시 확인합니다.",
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
            when (val rewardWindow = observed.rewardWindow) {
                is RaidRewardWindowObservation.Incomplete -> {
                    cycle.rewardRecovery?.takeIf { it.held }?.let { recovery ->
                        return rewardHeldDirective(configuration, cycle, recovery)
                    }
                    return observeIncompleteRewardWindow(accountId, configuration, cycle)
                }
                RaidRewardWindowObservation.Absent -> {
                    return RaidDirective.Complete(
                        outcome = store.finish(
                            accountId = accountId,
                            raidId = cycle.raidId,
                            outcome = RaidCycleOutcomeKind.SUPERSEDED_BY_OBSERVED_RAID,
                            now = timeProvider.now(),
                        ),
                        reasonCode = "RAID_REWARD_WINDOW_ABSENT_SUPERSEDED",
                        message = "보상 가능 창이 사라져 외부 상태 진전으로 현재 레이드 사이클을 종결합니다.",
                    )
                }
                RaidRewardWindowObservation.Available,
                is RaidRewardWindowObservation.ClaimWindow,
                -> {
                    cycle.rewardRecovery?.takeIf {
                        it.held && it.kind == RaidRewardRecoveryKind.ACTION_RESULT
                    }?.let { recovery ->
                        return rewardHeldDirective(configuration, cycle, recovery)
                    }
                    cycle.rewardRecovery?.takeIf {
                        it.kind == RaidRewardRecoveryKind.WINDOW_OBSERVATION
                    }?.let { recovery ->
                        cycle = if (recovery.retryCount == 0) {
                            store.clearRewardRecovery(accountId, cycle.raidId, timeProvider.now())
                        } else {
                            store.saveRewardRecovery(
                                accountId,
                                cycle.raidId,
                                recovery.copy(held = false, successfulObservationCount = 0),
                                timeProvider.now(),
                            )
                        }
                    }
                }
            }
            if (
                observed.rewardWindow == RaidRewardWindowObservation.Available ||
                observed.rewardWindow is RaidRewardWindowObservation.ClaimWindow
            ) {
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
            if (cycle.battleSafetyVersion < CURRENT_RAID_BATTLE_SAFETY_VERSION) {
                cycle = initializeDeploymentSafetyGate(accountId, cycle, observed)
            }
            cycle.battleSafetyGate?.let { gate ->
                decideBattleSafetyGate(accountId, configuration, cycle, observed, observation.fresh, gate)?.let {
                    return it
                }
                cycle = requireNotNull(store.load(accountId).openCycle)
            }
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
                    diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                    impactScope = AutomationImpactScope.RAID_ONLY,
                    releaseCondition = "최신 레이드 전투 가능 상태를 완전하게 관측하면 새 판단",
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
                    cooldownSource = battle.cooldownSource ?: RaidCooldownSource.HOF_DIRECT,
                    impactScope = AutomationImpactScope.RAID_ONLY,
                    releaseCondition = "쿨타임 종료 뒤 최신 레이드 상태 재확인",
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
                at = registrationRetryAt(timeProvider.now(), observation.registrationWaitSeconds),
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

    private fun RaidDirective.resolveAuthoritativeState(observation: RaidObservation): RaidAuthoritativeState? {
        val raidId = when (this) {
            is RaidDirective.Execute -> null
            is RaidDirective.WaitUntil -> raidId
            is RaidDirective.Complete -> outcome.raidId
            is RaidDirective.Hold -> raidId?.takeIf { reason in AUTHORITATIVE_HOLD_REASONS }
        } ?: return null
        val target = observation.raids.singleOrNull { it.id == raidId }
        if (
            this is RaidDirective.WaitUntil &&
            reason in BATTLE_STATE_WAIT_REASONS &&
            target?.battleAvailability == RaidBattleAvailability.INCOMPLETE
        ) {
            return null
        }
        return observation.toAuthoritativeState(raidId)
    }

    private fun RaidObservation.toAuthoritativeState(raidId: String): RaidAuthoritativeState? {
        val target = raids.singleOrNull { it.id == raidId }
        if (
            target?.status in setOf(RaidObservedStatus.TESTING, RaidObservedStatus.UNKNOWN) ||
            target?.rewardWindow is RaidRewardWindowObservation.Incomplete
        ) {
            return null
        }
        val targetState = target?.let {
            RaidAuthoritativeTargetState(
                status = it.status,
                joined = it.joined,
                playable = it.playable,
                waitSeconds = it.waitSeconds,
                actions = it.actions,
                battleAvailability = it.battleAvailability,
                battleCategoryId = it.battle?.categoryId,
                battleMapCode = it.battle?.mapCode,
                battleCooldownRemainingSeconds = it.battle?.cooldownRemainingSeconds,
                rewardWindow = when (val reward = it.rewardWindow) {
                    RaidRewardWindowObservation.Available -> RaidAuthoritativeRewardWindow(
                        RaidAuthoritativeRewardWindowKind.AVAILABLE,
                    )
                    RaidRewardWindowObservation.Absent -> RaidAuthoritativeRewardWindow(
                        RaidAuthoritativeRewardWindowKind.ABSENT,
                    )
                    is RaidRewardWindowObservation.ClaimWindow -> RaidAuthoritativeRewardWindow(
                        RaidAuthoritativeRewardWindowKind.CLAIM_WINDOW,
                        reward.remainingSeconds,
                    )
                    is RaidRewardWindowObservation.Incomplete -> RaidAuthoritativeRewardWindow(
                        RaidAuthoritativeRewardWindowKind.INCOMPLETE,
                    )
                },
            )
        }
        return RaidAuthoritativeState(
            raidId = raidId,
            target = targetState,
            registrationWait = registrationWait,
            registrationWaitSeconds = registrationWaitSeconds,
            globalActions = globalActions,
        )
    }

    private fun RaidDirective.asDecision() = RaidDecision(this)

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
            val now = timeProvider.now()
            store.load(accountId).openCycle
                ?.takeIf { cycle -> cycle.raidId == attempt.raidId }
                ?.let { cycle ->
                    createFallbackGate(attempt, attempt.finishedAt ?: now)?.let { gate ->
                        store.saveBattleSafetyGate(accountId, cycle.raidId, gate, now)
                    }
                    if (cycle.battleRecovery != null) {
                        store.clearBattleRecovery(accountId, cycle.raidId, now)
                    }
                }
            return RaidRecordResult.Recorded()
        }
        if (observation is RaidResultObservation.BattleAmbiguous) {
            return recordAmbiguousBattle(accountId, attempt, observation)
        }
        val page = (observation as? RaidResultObservation.Page)?.value
            ?: return needsRecheck("레이드 화면 관측 결과가 필요합니다.")
        val accountState = store.load(accountId)
        val registrationAvailable = page.fresh && !page.registrationWait &&
            page.raids.none(RaidObservedTarget::joined) &&
            page.raids.singleOrNull { it.id == attempt.raidId }
                ?.let { registrationIsRunnable(it) && !requiresReset(it) } == true &&
            accountState.configuration?.takeIf { it.enabled && it.entryId == attempt.entryId }
                ?.targets?.any { it.raidId == attempt.raidId } == true &&
            (accountState.openCycle == null || accountState.openCycle.raidId == attempt.raidId)
        if (attempt.kind == RaidIntentKind.REFRESH && page.directRefreshResponse && registrationAvailable) {
            convergence?.allowRaidRegistrationFreshDecision(
                accountId, attempt.entryId, attempt.raidId, timeProvider.now(),
            )
        }
        val cycle = accountState.openCycle ?: if (attempt.kind == RaidIntentKind.REFRESH) {
            return recordInitialRefresh(accountId, attempt, page, accountState)
        } else {
            return needsRecheck("확정할 열린 레이드 사이클이 없습니다.")
        }
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
                return nextCheckAt?.takeIf { target.status in REGISTRATION_STATUSES }?.let { at ->
                    RaidRecordResult.EntryWait(
                        at,
                        target.id,
                        "레이드 출발 가능 시각 뒤 상태를 다시 갱신합니다.",
                        reasonCode = "RAID_WAITING_TO_START",
                        releaseCondition = "출발 가능 시각 뒤 상태 갱신",
                    )
                } ?: RaidRecordResult.Recorded()
            }
            if (RaidRegistrationResultEvidence.hasStaleBattleConflict(page.resultMessages)) {
                store.transition(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    status = RaidAutomationCycleStatus.REGISTRATION_REFRESH_REQUIRED,
                    observedStatus = "REGISTRATION_CONFLICT",
                    nextCheckAt = null,
                    now = timeProvider.now(),
                )
                return RaidRecordResult.Recorded()
            }
            if (page.registrationWait) {
                val now = timeProvider.now()
                val at = registrationRetryAt(now, page.registrationWaitSeconds)
                store.transition(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    status = RaidAutomationCycleStatus.REGISTRATION_COOLDOWN,
                    observedStatus = "REGISTRATION_COOLDOWN",
                    nextCheckAt = at,
                    now = now,
                )
                return RaidRecordResult.EntryWait(
                    at,
                    attempt.raidId,
                    "레이드 전역 등록 쿨타임 종료 뒤 상태를 다시 갱신합니다.",
                    reasonCode = "RAID_GLOBAL_REGISTRATION_COOLDOWN",
                    releaseCondition = "전역 등록 쿨타임 종료 뒤 상태 갱신",
                )
            }
        }
        if (
            attempt.kind == RaidIntentKind.REFRESH &&
            cycle.status != RaidAutomationCycleStatus.POST_REWARD_CHECK
        ) {
            return recordOpenCycleRefresh(accountId, attempt, page, cycle)
        }
        if (attempt.kind == RaidIntentKind.START) {
            val target = page.raids.singleOrNull { it.id == attempt.raidId }
            if (
                page.actionSuccessMarker &&
                target?.status in setOf(RaidObservedStatus.IN_BATTLE, RaidObservedStatus.COMPLETED)
            ) {
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
            if (cycle.rewardRecovery != null) {
                store.clearRewardRecovery(accountId, attempt.raidId, timeProvider.now())
            }
            store.transition(
                accountId = accountId,
                raidId = attempt.raidId,
                status = RaidAutomationCycleStatus.POST_REWARD_CHECK,
                observedStatus = page.raids.singleOrNull { it.id == attempt.raidId }?.statusText,
                nextCheckAt = null,
                now = timeProvider.now(),
            )
            return RaidRecordResult.Recorded()
        }
        if (
            attempt.kind == RaidIntentKind.REWARD &&
            page.fresh &&
            RaidIntentKind.REWARD in page.globalActions &&
            page.raids.singleOrNull { it.id == attempt.raidId }?.rewardWindow?.let { rewardWindow ->
                rewardWindow == RaidRewardWindowObservation.Available ||
                    rewardWindow is RaidRewardWindowObservation.ClaimWindow
            } == true
        ) {
            return recordAmbiguousReward(accountId, attempt, cycle, page.observedAt ?: timeProvider.now())
        }
        if (
            attempt.kind == RaidIntentKind.REFRESH &&
            cycle.status == RaidAutomationCycleStatus.POST_REWARD_CHECK &&
            page.fresh &&
            (page.applied || page.actionSuccessMarker)
        ) {
            val now = timeProvider.now()
            val completion = store.finish(
                accountId = accountId,
                raidId = attempt.raidId,
                outcome = RaidCycleOutcomeKind.COMPLETED,
                now = now,
                advanceRotation = shouldAdvanceRotation(accountId, attempt.raidId),
            )
            return if (page.registrationWait) {
                RaidRecordResult.EntryWait(
                    at = registrationRetryAt(now, page.registrationWaitSeconds),
                    raidId = attempt.raidId,
                    message = "레이드 전역 등록 쿨타임 종료 뒤 상태를 다시 갱신합니다.",
                    completion = completion,
                    reasonCode = "RAID_POST_REWARD_GLOBAL_COOLDOWN",
                    releaseCondition = "보상 완료 뒤 전역 등록 쿨타임 종료",
                )
            } else {
                RaidRecordResult.Recorded(completion)
            }
        }
        if (attempt.kind == RaidIntentKind.REFRESH && postRewardStateIsProven(page, attempt.raidId)) {
            val now = timeProvider.now()
            val completion = store.finish(
                accountId = accountId,
                raidId = attempt.raidId,
                outcome = RaidCycleOutcomeKind.COMPLETED,
                now = now,
                advanceRotation = shouldAdvanceRotation(accountId, attempt.raidId),
            )
            return if (page.registrationWait) {
                RaidRecordResult.EntryWait(
                    at = registrationRetryAt(now, page.registrationWaitSeconds),
                    raidId = attempt.raidId,
                    message = "레이드 전역 등록 쿨타임 종료 뒤 상태를 다시 갱신합니다.",
                    completion = completion,
                    reasonCode = "RAID_POST_REWARD_GLOBAL_COOLDOWN",
                    releaseCondition = "보상 완료 뒤 전역 등록 쿨타임 종료",
                )
            } else {
                RaidRecordResult.Recorded(completion)
            }
        }
        if (attempt.kind == RaidIntentKind.REGISTER && registrationAvailable) {
            return RaidRecordResult.FreshDecision(
                "신청 가능한 레이드 상태를 확인해 새 참가 신청을 판단합니다.",
            )
        }
        if (actionIsProvablyNotApplied(page, attempt)) {
            if (attempt.kind == RaidIntentKind.REWARD) {
                val now = timeProvider.now()
                store.transition(
                    accountId = accountId,
                    raidId = attempt.raidId,
                    status = RaidAutomationCycleStatus.REWARD_PENDING,
                    observedStatus = page.raids.singleOrNull { it.id == attempt.raidId }?.statusText,
                    nextCheckAt = now.plusSeconds(SAFETY_RECHECK_SECONDS),
                    now = now,
                )
            }
            return RaidRecordResult.NotApplied("권위 상태에서 이전 레이드 요청이 적용되지 않은 것을 확인했습니다.")
        }
        return needsRecheck("레이드 실행 결과가 아직 적용을 증명하지 못했습니다.")
    }

    private fun recordInitialRefresh(
        accountId: Long,
        attempt: RaidAttempt,
        page: RaidObservation,
        state: RaidCycleAccountState,
    ): RaidRecordResult {
        val configuration = state.configuration
            ?: return RaidRecordResult.EntrySkipped("레이드 자동화 설정을 찾을 수 없습니다.")
        if (!page.fresh) {
            return needsRecheck("상태 갱신 직접 응답이 완전하지 않아 다시 확인합니다.")
        }
        val joined = page.raids.filter(RaidObservedTarget::joined)
        if (joined.size > 1) {
            return RaidRecordResult.EntryWait(
                at = timeProvider.now().plusSeconds(MANUAL_RAID_RECHECK_SECONDS),
                raidId = attempt.raidId,
                message = "여러 레이드 참가 상태가 관측되어 자동 행동 없이 10분 뒤 다시 갱신합니다.",
            )
        }
        joined.singleOrNull()
            ?.takeIf { active -> configuration.targets.none { it.raidId == active.id } }
            ?.let { manual ->
                return RaidRecordResult.EntryWait(
                    at = timeProvider.now().plusSeconds(MANUAL_RAID_RECHECK_SECONDS),
                    raidId = manual.id,
                    message = "설정 밖 수동 레이드는 건드리지 않고 10분 뒤 다시 확인합니다.",
                    reasonCode = "RAID_MANUAL_UNCONFIGURED_ACTIVE",
                    releaseCondition = "수동 레이드 종료 또는 자동화 설정 편입 뒤 상태 갱신",
                )
            }
        val configuredJoined = joined.singleOrNull()?.takeIf { observed ->
            configuration.targets.any { it.raidId == observed.id }
        }
        if (page.registrationWait && configuredJoined == null) {
            return RaidRecordResult.EntryWait(
                at = registrationRetryAt(timeProvider.now(), page.registrationWaitSeconds),
                raidId = attempt.raidId,
                message = "레이드 전역 등록 쿨타임 종료 뒤 상태를 다시 갱신합니다.",
                reasonCode = "RAID_GLOBAL_REGISTRATION_COOLDOWN",
                releaseCondition = "전역 등록 쿨타임 종료 뒤 상태 갱신",
            )
        }
        val selected = configuredJoined?.let { observed ->
            configuration.targets.single { it.raidId == observed.id } to observed
        } ?: configuration.targets.singleOrNull { it.raidId == attempt.raidId }?.let { target ->
            page.raids.singleOrNull { it.id == target.raidId }?.let { target to it }
        }
        val (target, observed) = selected
            ?: return RaidRecordResult.EntryWait(
                at = timeProvider.now().plusSeconds(MANUAL_RAID_RECHECK_SECONDS),
                raidId = attempt.raidId,
                message = "설정된 레이드의 최신 상태를 확인하지 못해 10분 뒤 다시 갱신합니다.",
            )
        if (!observed.joined) {
            if (observed.status == RaidObservedStatus.CLOSED) {
                return RaidRecordResult.EntrySkipped("현재 순환 레이드가 닫혀 있어 이번 판단에서 건너뜁니다.")
            }
            if (observed.status in setOf(RaidObservedStatus.TESTING, RaidObservedStatus.UNKNOWN)) {
                return RaidRecordResult.EntryWait(
                    at = timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
                    raidId = observed.id,
                    message = "레이드 상태를 안전하게 판단할 수 없어 다시 갱신합니다.",
                )
            }
            if (
                !registrationIsRunnable(observed) &&
                !requiresReset(observed) &&
                observed.status in EXTERNAL_ACTIVE_STATUSES
            ) {
                val at = (observed.rewardWindow as? RaidRewardWindowObservation.ClaimWindow)
                    ?.remainingSeconds
                    ?.let { timeProvider.now().plusSeconds(it.coerceAtLeast(1)) }
                    ?: timeProvider.now().plusSeconds(MANUAL_RAID_RECHECK_SECONDS)
                return RaidRecordResult.EntryWait(
                    at = at,
                    raidId = observed.id,
                    message = "다른 사용자가 진행 중인 설정 레이드는 자동 행동 없이 다시 확인합니다.",
                    reasonCode = "RAID_EXTERNAL_CONFIGURED_ACTIVE",
                    releaseCondition = "외부 전투 또는 보상 단계 종료 뒤 상태 갱신",
                )
            }
            if (!registrationIsRunnable(observed) && !requiresReset(observed)) {
                return RaidRecordResult.EntryWait(
                    at = timeProvider.now().plusSeconds(MANUAL_RAID_RECHECK_SECONDS),
                    raidId = observed.id,
                    message = "현재 참가하거나 진전시킬 수 없는 레이드를 10분 뒤 다시 갱신합니다.",
                )
            }
        }
        val status = if (observed.joined) observed.toCycleStatus() else RaidAutomationCycleStatus.PREPARING
        val nextCheckAt = observedCycleDeadline(observed)
        store.open(
            accountId = accountId,
            entryId = configuration.entryId,
            target = target,
            now = timeProvider.now(),
            status = status,
            observedStatus = observed.statusText,
            nextCheckAt = nextCheckAt,
        )
        return nextCheckAt?.let { at ->
            RaidRecordResult.EntryWait(
                at = at,
                raidId = observed.id,
                message = observedCycleWaitMessage(observed),
                reasonCode = observedCycleWaitReason(observed),
                releaseCondition = "관측된 레이드 시각 뒤 상태 갱신",
            )
        } ?: RaidRecordResult.Recorded()
    }

    private fun recordOpenCycleRefresh(
        accountId: Long,
        attempt: RaidAttempt,
        page: RaidObservation,
        cycle: RaidCycleSnapshot,
    ): RaidRecordResult {
        if (!page.fresh) return needsRecheck("상태 갱신 직접 응답이 완전하지 않아 다시 확인합니다.")
        val now = timeProvider.now()
        val target = page.raids.singleOrNull { it.id == attempt.raidId }
        if (page.registrationWait && target?.joined != true) {
            val at = registrationRetryAt(now, page.registrationWaitSeconds)
            store.transition(
                accountId,
                attempt.raidId,
                RaidAutomationCycleStatus.REGISTRATION_COOLDOWN,
                "REGISTRATION_COOLDOWN",
                at,
                now,
            )
            return RaidRecordResult.EntryWait(
                at,
                attempt.raidId,
                "레이드 전역 등록 쿨타임 종료 뒤 다시 갱신합니다.",
                reasonCode = "RAID_GLOBAL_REGISTRATION_COOLDOWN",
                releaseCondition = "전역 등록 쿨타임 종료 뒤 상태 갱신",
            )
        }
        val observedTarget = target ?: run {
            val at = now.plusSeconds(MANUAL_RAID_RECHECK_SECONDS)
            store.transition(accountId, attempt.raidId, cycle.status, "TARGET_MISSING_AFTER_REFRESH", at, now)
            return RaidRecordResult.EntryWait(at, attempt.raidId, "레이드 대상이 보이지 않아 10분 뒤 다시 갱신합니다.")
        }
        if (observedTarget.joined) {
            val at = observedCycleDeadline(observedTarget)
            store.transition(
                accountId,
                attempt.raidId,
                observedTarget.toCycleStatus(),
                observedTarget.statusText,
                at,
                now,
            )
            return at?.let {
                RaidRecordResult.EntryWait(
                    at = it,
                    raidId = observedTarget.id,
                    message = observedCycleWaitMessage(observedTarget),
                    reasonCode = observedCycleWaitReason(observedTarget),
                    releaseCondition = "관측된 레이드 시각 뒤 상태 갱신",
                )
            } ?: RaidRecordResult.Recorded()
        }
        if (registrationIsRunnable(observedTarget) || requiresReset(observedTarget)) {
            store.transition(
                accountId,
                attempt.raidId,
                RaidAutomationCycleStatus.PREPARING,
                observedTarget.statusText,
                null,
                now,
            )
            return RaidRecordResult.Recorded()
        }
        if (observedTarget.status in setOf(RaidObservedStatus.IN_BATTLE, RaidObservedStatus.COMPLETED, RaidObservedStatus.CLOSED)) {
            return RaidRecordResult.Recorded(
                store.finish(
                    accountId,
                    attempt.raidId,
                    RaidCycleOutcomeKind.ABORTED_REGISTRATION_LOST,
                    now,
                ),
            )
        }
        val at = now.plusSeconds(MANUAL_RAID_RECHECK_SECONDS)
        store.transition(accountId, attempt.raidId, cycle.status, observedTarget.statusText, at, now)
        return RaidRecordResult.EntryWait(at, observedTarget.id, "현재 레이드를 진전시킬 수 없어 10분 뒤 다시 갱신합니다.")
    }

    private fun observedCycleDeadline(target: RaidObservedTarget): Instant? = when {
        target.status in REGISTRATION_STATUSES -> target.waitSeconds
            ?.takeIf { it > 0 }
            ?.let { timeProvider.now().plusSeconds(it.coerceAtLeast(MINIMUM_WAIT_SECONDS).toLong()) }
        target.status == RaidObservedStatus.IN_BATTLE -> target.battle?.cooldownRemainingSeconds
            ?.takeIf { it > 0 }
            ?.let { timeProvider.now().plusSeconds(it) }
        else -> null
    }

    private fun observedCycleWaitMessage(target: RaidObservedTarget): String =
        if (target.status == RaidObservedStatus.IN_BATTLE) {
            "개인 레이드 전투 쿨타임 종료 뒤 상태를 다시 갱신합니다."
        } else {
            "레이드 출발 가능 시각 뒤 상태를 다시 갱신합니다."
        }

    private fun observedCycleWaitReason(target: RaidObservedTarget): String =
        if (target.status == RaidObservedStatus.IN_BATTLE) {
            "RAID_PERSONAL_BATTLE_COOLDOWN"
        } else {
            "RAID_WAITING_TO_START"
        }

    private fun completeCycle(accountId: Long, cycle: RaidCycleSnapshot): RaidDirective.Complete {
        val recoveryWasSuperseded = cycle.battleRecovery != null
        return RaidDirective.Complete(
            store.finish(
            accountId = accountId,
            raidId = cycle.raidId,
            outcome = RaidCycleOutcomeKind.COMPLETED,
            now = timeProvider.now(),
            advanceRotation = shouldAdvanceRotation(accountId, cycle.raidId),
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
        createFallbackGate(attempt, now)?.let { gate ->
            val existingGate = cycle.battleSafetyGate
            if (existingGate == null || existingGate.notBefore.isBefore(gate.notBefore)) {
                store.saveBattleSafetyGate(accountId, attempt.raidId, gate, now)
            }
        }
        store.saveBattleRecovery(accountId, attempt.raidId, recovery, now)
        return RaidRecordResult.BattleRecoveryStarted(
            recovery.nextCheckAt,
            recoveryWarning(recovery, observation.reason),
        )
    }

    private fun recordAmbiguousReward(
        accountId: Long,
        attempt: RaidAttempt,
        cycle: RaidCycleSnapshot,
        observedAt: java.time.Instant,
    ): RaidRecordResult {
        val executionIdentity = attempt.executionIdentity
            ?: return needsRecheck("불명확한 레이드 보상 요청의 실행 식별자가 없습니다.")
        val existing = cycle.rewardRecovery
        if (existing?.held == true) {
            return RaidRecordResult.RewardHeld("두 번째 불명확한 보상 요청 뒤 이 레이드 보상을 보류합니다.")
        }
        val current = if (existing == null || existing.executionIdentity != executionIdentity) {
            RaidRewardRecovery(
                executionIdentity = executionIdentity,
                firstAmbiguousAt = observedAt,
                successfulObservationCount = 1,
                retryCount = existing?.retryCount ?: 0,
            )
        } else {
            existing.copy(successfulObservationCount = existing.successfulObservationCount + 1)
        }
        val exhausted = current.successfulObservationCount >= MAX_REWARD_OBSERVATIONS ||
            !observedAt.isBefore(current.firstAmbiguousAt.plusSeconds(MAX_REWARD_OBSERVATION_SECONDS))
        if (!exhausted) {
            store.saveRewardRecovery(accountId, cycle.raidId, current, observedAt)
            return RaidRecordResult.NeedsRecheck(
                observedAt.plusSeconds(SAFETY_RECHECK_SECONDS),
                "보상 결과를 POST 재전송 없이 읽기 전용으로 확인합니다. " +
                    "성공 관측 ${current.successfulObservationCount}/$MAX_REWARD_OBSERVATIONS",
            )
        }
        return if (current.retryCount == 0) {
            store.saveRewardRecovery(
                accountId,
                cycle.raidId,
                current.copy(retryCount = 1),
                observedAt,
            )
            RaidRecordResult.RewardRetryReady(
                "첫 보상 요청을 종결하고 최신 완전 상태에서 새 실행 식별자로 한 번만 다시 시도합니다.",
            )
        } else {
            val held = current.copy(held = true)
            store.saveRewardRecovery(accountId, cycle.raidId, held, observedAt)
            RaidRecordResult.RewardHeld(
                "두 번째 보상 요청도 결과를 확정하지 못해 이 레이드 보상만 수동 확인으로 보류합니다.",
            )
        }
    }

    private fun observeIncompleteRewardWindow(
        accountId: Long,
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
    ): RaidDirective.Hold {
        val now = timeProvider.now()
        val existing = cycle.rewardRecovery
        val current = if (existing?.kind == RaidRewardRecoveryKind.WINDOW_OBSERVATION) {
            existing.copy(successfulObservationCount = existing.successfulObservationCount + 1)
        } else {
            RaidRewardRecovery(
                executionIdentity = null,
                firstAmbiguousAt = now,
                successfulObservationCount = 1,
                retryCount = existing?.retryCount ?: 0,
                kind = RaidRewardRecoveryKind.WINDOW_OBSERVATION,
            )
        }
        val held = current.successfulObservationCount >= MAX_REWARD_OBSERVATIONS ||
            !now.isBefore(current.firstAmbiguousAt.plusSeconds(MAX_REWARD_OBSERVATION_SECONDS))
        val updated = current.copy(held = held)
        store.saveRewardRecovery(accountId, cycle.raidId, updated, now)
        if (held) return rewardHeldDirective(configuration, cycle, updated)
        return RaidDirective.Hold(
            reason = RaidHoldReason.ACTION_UNAVAILABLE,
            message = "보상 확인 가능 상태를 POST 없이 다시 관측합니다. " +
                "성공 관측 ${updated.successfulObservationCount}/$MAX_REWARD_OBSERVATIONS",
            recheckAt = now.plusSeconds(SAFETY_RECHECK_SECONDS),
            entryId = configuration.entryId,
            raidId = cycle.raidId,
            reasonCode = "RAID_REWARD_WINDOW_INCOMPLETE",
            diagnosticKind = AutomationDiagnosticKind.RAID_REWARD_RESULT_RECHECK,
            impactScope = AutomationImpactScope.RAID_ONLY,
            releaseCondition = "최대 5회 또는 2분 안에 최신 보상 가능 상태를 완전하게 관측",
        )
    }

    private fun rewardHeldDirective(
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
        recovery: RaidRewardRecovery,
    ): RaidDirective.Hold = when (recovery.kind) {
        RaidRewardRecoveryKind.WINDOW_OBSERVATION -> RaidDirective.Hold(
            reason = RaidHoldReason.ACTION_UNAVAILABLE,
            message = "보상 가능 상태를 제한된 관측 예산 안에 확정하지 못해 이 레이드 보상만 보류합니다.",
            entryId = configuration.entryId,
            raidId = cycle.raidId,
            reasonCode = "RAID_REWARD_OBSERVATION_HELD",
            diagnosticKind = AutomationDiagnosticKind.RAID_REWARD_OBSERVATION_HELD,
            impactScope = AutomationImpactScope.RAID_ONLY,
            releaseCondition = "외부 보상 상태 변경 또는 수동 확인 뒤 새 판단",
        )
        RaidRewardRecoveryKind.ACTION_RESULT -> RaidDirective.Hold(
            reason = RaidHoldReason.ACTION_UNAVAILABLE,
            message = "두 번째 보상 요청도 결과를 확정하지 못해 이 레이드 보상만 수동 확인으로 보류합니다.",
            entryId = configuration.entryId,
            raidId = cycle.raidId,
            reasonCode = "RAID_REWARD_RESULT_HELD",
            diagnosticKind = AutomationDiagnosticKind.RAID_REWARD_RESULT_HELD,
            impactScope = AutomationImpactScope.RAID_ONLY,
            releaseCondition = "수동 확인 또는 외부 상태 변경 뒤 새 판단",
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
                diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 레이드 전투 가능 상태를 완전하게 관측하면 새 판단",
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
                diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 레이드 전투 세부 정보를 완전하게 관측하면 새 판단",
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
                diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 레이드 전투 세부 정보를 완전하게 관측하면 새 판단",
            )
        }
        if (observed.battleAvailability == RaidBattleAvailability.COOLDOWN) {
            val superseded =
                recovery.submittedFromRunnable &&
                    battle.categoryId == recovery.categoryId &&
                    battle.mapCode == recovery.mapCode
            if (superseded) {
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
                at = now.plusSeconds(seconds),
                reason = RaidWaitReason.BATTLE_COOLDOWN,
                message = if (superseded) {
                    "새 쿨타임을 관측했지만 현재 요청의 단말 결과가 없어 외부 상태 변경으로 처리합니다. " +
                        "다음 전투 가능 시각까지 기다립니다."
                } else {
                    recoveryWarning(recovery, "제출 전 관측이 없어 현재 쿨타임만으로 적용을 추정하지 않습니다.")
                },
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                cooldownSource = battle.cooldownSource ?: RaidCooldownSource.HOF_DIRECT,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "쿨타임 종료 뒤 최신 레이드 상태 재확인",
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

    private fun createFallbackGate(attempt: RaidAttempt, startedAt: java.time.Instant): RaidBattleSafetyGate? {
        if (!properties.fallbackEnforcementEnabled || attempt.kind != RaidIntentKind.BATTLE) return null
        val categoryId = attempt.categoryId ?: return null
        val mapCode = attempt.mapCode ?: return null
        return RaidBattleSafetyGate(
            raidId = attempt.raidId,
            categoryId = categoryId,
            mapCode = mapCode,
            executionIdentity = attempt.executionIdentity,
            startedAt = startedAt,
            notBefore = startedAt.plus(properties.fallbackCooldown),
            source = RaidCooldownSource.LOCAL_FALLBACK,
        )
    }

    private fun initializeDeploymentSafetyGate(
        accountId: Long,
        cycle: RaidCycleSnapshot,
        observed: RaidObservedTarget,
    ): RaidCycleSnapshot {
        val now = timeProvider.now()
        val battle = observed.battle
        val hofSeconds = battle?.cooldownRemainingSeconds?.takeIf { it > 0 }
        val gate = when {
            hofSeconds != null -> RaidBattleSafetyGate(
                raidId = cycle.raidId,
                categoryId = battle.categoryId,
                mapCode = battle.mapCode,
                executionIdentity = null,
                startedAt = now,
                notBefore = now.plusSeconds(hofSeconds),
                source = battle.cooldownSource ?: RaidCooldownSource.HOF_DIRECT,
            )
            properties.fallbackEnforcementEnabled -> RaidBattleSafetyGate(
                raidId = cycle.raidId,
                categoryId = battle?.categoryId,
                mapCode = battle?.mapCode,
                executionIdentity = null,
                startedAt = now,
                notBefore = now.plus(properties.fallbackCooldown),
                source = RaidCooldownSource.DEPLOYMENT_FALLBACK,
            )
            else -> null
        }
        return if (gate == null) {
            store.clearBattleSafetyGate(accountId, cycle.raidId, now)
        } else {
            store.saveBattleSafetyGate(accountId, cycle.raidId, gate, now)
        }
    }

    private fun decideBattleSafetyGate(
        accountId: Long,
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
        observed: RaidObservedTarget,
        fresh: Boolean,
        gate: RaidBattleSafetyGate,
    ): RaidDirective? {
        val now = timeProvider.now()
        val battle = observed.battle
        val hofSeconds = battle?.cooldownRemainingSeconds?.takeIf { it > 0 }
        if (observed.battleAvailability == RaidBattleAvailability.COOLDOWN && hofSeconds != null) {
            val supersededRecovery = cycle.battleRecovery?.let { recovery ->
                recovery.submittedFromRunnable &&
                    battle.categoryId == recovery.categoryId &&
                    battle.mapCode == recovery.mapCode
            } == true
            if (supersededRecovery) {
                store.clearBattleRecovery(accountId, cycle.raidId, now)
            }
            val authoritative = gate.copy(
                categoryId = battle.categoryId,
                mapCode = battle.mapCode,
                notBefore = now.plusSeconds(hofSeconds),
                source = battle.cooldownSource ?: RaidCooldownSource.HOF_DIRECT,
                lastObservedAt = now,
            )
            store.saveBattleSafetyGate(accountId, cycle.raidId, authoritative, now)
            return battleSafetyWait(configuration, cycle, authoritative, supersededRecovery)
        }
        if (!properties.enforces(gate)) {
            store.clearBattleSafetyGate(accountId, cycle.raidId, now)
            return null
        }
        if (gate.held) {
            return safetyHeld(configuration, cycle, gate)
        }
        if (now.isBefore(gate.notBefore)) {
            return battleSafetyWait(configuration, cycle, gate)
        }
        if (!fresh) {
            return RaidDirective.Hold(
                reason = RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                message = "안전 게이트 만료 뒤 최신 레이드 관측을 기다립니다.",
                recheckAt = now.plusSeconds(SAFETY_RECHECK_SECONDS),
                entryId = configuration.entryId,
                raidId = cycle.raidId,
                reasonCode = "RAID_BATTLE_GATE_FRESH_OBSERVATION_REQUIRED",
                diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 레이드 전투 가능 상태 재관측",
            )
        }
        if (observed.battleAvailability == RaidBattleAvailability.RUNNABLE && battle != null) {
            store.clearBattleSafetyGate(accountId, cycle.raidId, now)
            return null
        }
        if (observed.battleAvailability in setOf(
                RaidBattleAvailability.INCOMPLETE,
                RaidBattleAvailability.ABSENT,
            )
        ) {
            val firstIncompleteAt = gate.firstIncompleteAt ?: gate.notBefore
            val successfulObservations = gate.successfulIncompleteObservations + 1
            val held = successfulObservations >= MAX_SAFETY_OBSERVATIONS ||
                !now.isBefore(firstIncompleteAt.plusSeconds(MAX_SAFETY_OBSERVATION_SECONDS))
            val updated = gate.copy(
                firstIncompleteAt = firstIncompleteAt,
                successfulIncompleteObservations = successfulObservations,
                lastObservedAt = now,
                evidenceCaseId = observed.battleEvidenceCaseId ?: gate.evidenceCaseId,
                held = held,
            )
            store.saveBattleSafetyGate(accountId, cycle.raidId, updated, now)
            return if (held) {
                safetyHeld(configuration, cycle, updated)
            } else {
                RaidDirective.Hold(
                    reason = RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
                    message = "레이드 안전 게이트 해제를 위한 상태를 재확인합니다. " +
                        "성공 관측 $successfulObservations/$MAX_SAFETY_OBSERVATIONS",
                    recheckAt = now.plusSeconds(SAFETY_RECHECK_SECONDS),
                    entryId = configuration.entryId,
                    raidId = cycle.raidId,
                    reasonCode = "RAID_BATTLE_GATE_OBSERVATION_INCOMPLETE",
                    diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_AMBIGUOUS,
                    impactScope = AutomationImpactScope.RAID_ONLY,
                    releaseCondition = "최대 5회 또는 마감 후 2분까지 읽기 전용 재관측",
                )
            }
        }
        store.clearBattleSafetyGate(accountId, cycle.raidId, now)
        return null
    }

    private fun battleSafetyWait(
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
        gate: RaidBattleSafetyGate,
        supersededRecovery: Boolean = false,
    ) = RaidDirective.WaitUntil(
        at = gate.notBefore,
        reason = RaidWaitReason.BATTLE_COOLDOWN,
        message = if (supersededRecovery) {
            "새 HOF 쿨타임을 관측했지만 현재 요청의 단말 결과가 없어 외부 상태 변경으로 처리합니다. " +
                "다음 전투 가능 시각까지 기다립니다."
        } else when (gate.source) {
            RaidCooldownSource.HOF_DIRECT -> "HOF가 제공한 다음 레이드 전투 가능 시각까지 기다립니다."
            RaidCooldownSource.HOF_SINGLE_TARGET_INFERENCE ->
                "현재 단일 레이드에 귀속한 HOF 전투 가능 시각까지 기다립니다."
            RaidCooldownSource.LOCAL_FALLBACK,
            RaidCooldownSource.DEPLOYMENT_FALLBACK ->
                "HOF 쿨타임을 확정하지 못해 레이드 전용 안전 시간까지 기다립니다."
        },
        entryId = configuration.entryId,
        raidId = cycle.raidId,
        cooldownSource = gate.source,
        impactScope = AutomationImpactScope.RAID_ONLY,
        releaseCondition = "마감 뒤 최신 레이드 상태에서 실행 가능 여부 확인",
        reasonCode = "RAID_BATTLE_SAFETY_GATE",
    )

    private fun safetyHeld(
        configuration: RaidCycleConfiguration,
        cycle: RaidCycleSnapshot,
        gate: RaidBattleSafetyGate,
    ) = RaidDirective.Hold(
        reason = RaidHoldReason.BATTLE_OBSERVATION_INCOMPLETE,
        message = "레이드 전투 가능 상태를 제한된 관측 예산 안에 확정하지 못해 이 레이드 전투만 보류합니다.",
        entryId = configuration.entryId,
        raidId = cycle.raidId,
        reasonCode = "RAID_BATTLE_GATE_HELD_${gate.successfulIncompleteObservations}",
        diagnosticKind = AutomationDiagnosticKind.RAID_COOLDOWN_OBSERVATION_HELD,
        impactScope = AutomationImpactScope.RAID_ONLY,
        releaseCondition = "외부 상태 변경 또는 수동 확인 뒤 새 판단",
    )

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
        if (page.rewardResult != null) return true
        if (RaidIntentKind.REWARD in page.globalActions) return false
        val target = page.raids.singleOrNull { it.id == raidId }
        return page.applied ||
            page.registrationWait ||
            target?.let(::requiresReset) == true ||
            (target != null && !target.joined && target.status in REGISTRATION_STATUSES)
    }

    private fun postRewardStateIsProven(page: RaidObservation, raidId: String): Boolean {
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
            RaidIntentKind.REWARD -> false
            RaidIntentKind.REFRESH -> RaidIntentKind.REFRESH in page.globalActions &&
                !postRewardStateIsProven(page, attempt.raidId)
            RaidIntentKind.BATTLE -> false
        }
    }

    private fun needsRecheck(message: String) = RaidRecordResult.NeedsRecheck(
        timeProvider.now().plusSeconds(DEFAULT_RECHECK_SECONDS),
        message,
    )

    private fun registrationRetryAt(now: java.time.Instant, observedSeconds: Int?): java.time.Instant =
        now.plusSeconds(
            observedSeconds
                ?.takeIf { it > 0 }
                ?.coerceAtLeast(MINIMUM_WAIT_SECONDS)
                ?.toLong()
                ?: DEFAULT_RECHECK_SECONDS,
        )

    private fun rotate(targets: List<RaidCycleTarget>, currentTargetKey: String?): List<RaidCycleTarget> {
        val ordered = targets.sortedWith(compareBy(RaidCycleTarget::executionOrder, RaidCycleTarget::raidId))
        val index = ordered.indexOfFirst { it.raidId == currentTargetKey }
        return if (index <= 0) ordered else ordered.drop(index) + ordered.take(index)
    }

    private fun requiresReset(target: RaidObservedTarget): Boolean =
        target.statusText?.let(RESET_REQUIRED_STATUS::containsMatchIn) == true

    private fun registrationIsRunnable(target: RaidObservedTarget): Boolean =
        target.playable &&
            target.status in REGISTRATION_STATUSES &&
            RaidIntentKind.REGISTER in target.actions

    private fun shouldAdvanceRotation(accountId: Long, raidId: String): Boolean =
        store.load(accountId).configuration?.currentTargetKey == raidId

    private fun RaidObservedTarget.toCycleStatus(): RaidAutomationCycleStatus = when (status) {
        RaidObservedStatus.IN_BATTLE -> RaidAutomationCycleStatus.IN_BATTLE
        RaidObservedStatus.COMPLETED -> RaidAutomationCycleStatus.REWARD_PENDING
        else -> RaidAutomationCycleStatus.REGISTERED_WAITING
    }

    private companion object {
        const val DEFAULT_RECHECK_SECONDS = 30L
        const val MANUAL_RAID_RECHECK_SECONDS = 600L
        const val RECOVERY_RECHECK_SECONDS = 300L
        const val SAFETY_RECHECK_SECONDS = 10L
        const val MAX_SAFETY_OBSERVATIONS = 5
        const val MAX_SAFETY_OBSERVATION_SECONDS = 120L
        const val MAX_REWARD_OBSERVATIONS = 5
        const val MAX_REWARD_OBSERVATION_SECONDS = 120L
        const val MINIMUM_WAIT_SECONDS = 5
        val AUTHORITATIVE_HOLD_REASONS = setOf(
            RaidHoldReason.INVALID_PRESET,
            RaidHoldReason.MANUAL_RAID_ACTIVE,
            RaidHoldReason.EXTERNAL_RAID_ACTIVE,
            RaidHoldReason.TARGET_TEMPORARILY_MISSING,
            RaidHoldReason.ACTION_UNAVAILABLE,
            RaidHoldReason.BATTLE_TARGET_ABSENT,
        )
        val BATTLE_STATE_WAIT_REASONS = setOf(
            RaidWaitReason.BATTLE_COOLDOWN,
            RaidWaitReason.BATTLE_APPLIED_COOLDOWN,
            RaidWaitReason.BATTLE_RECOVERY_RECHECK,
        )
        val RESET_REQUIRED_STATUS = Regex("보상\\s*확인\\s*종료\\s*\\(\\s*리셋\\s*가능\\s*\\)")
        val REGISTRATION_STATUSES = setOf(
            RaidObservedStatus.RECRUITING,
            RaidObservedStatus.WAITING,
            RaidObservedStatus.READY,
        )
        val EXTERNAL_ACTIVE_STATUSES = REGISTRATION_STATUSES + setOf(
            RaidObservedStatus.IN_BATTLE,
            RaidObservedStatus.COMPLETED,
        )
        val REGISTRATION_RECOVERY_STATUSES = setOf(
            RaidAutomationCycleStatus.REGISTRATION_REFRESH_REQUIRED,
            RaidAutomationCycleStatus.REGISTRATION_COOLDOWN,
        )
    }
}
