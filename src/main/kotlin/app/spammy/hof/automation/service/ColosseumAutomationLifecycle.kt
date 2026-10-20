package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.ColosseumObservedState
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.town.common.service.ObservedTownActionPreconditionChangedException
import app.spammy.hof.town.common.service.AccountHofObservationInvalidatedException
import app.spammy.hof.town.pvp.service.ColosseumAutomationObservation
import app.spammy.hof.town.pvp.service.ColosseumService
import java.io.IOException
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class ColosseumAutomationLifecycle(
    private val service: ColosseumService,
    private val ownership: AutomationWorkOwnership,
    private val work: AutomationWorkLifecycle,
    private val recovery: HofSessionRecoveryExecutor,
    private val clock: TimeProvider,
) {
    fun describe() = AutomationActionDescriptor(AutomationType.COLOSSEUM, "COLOSSEUM", "COLOSSEUM_CHALLENGE",
        "콜로세움 도전", "저장된 5인 팀으로 랭킹 도전", targetKey = COLOSSEUM_TARGET, targetName = "콜로세움")

    fun prepare(accountId: Long, entryId: Long, action: ColosseumAutomationAction): ManagedAutomationAction {
        require(action.accountId == accountId)
        ownership.ensure(accountId, entryId, AutomationWorkAssignment(AutomationWorkType.COLOSSEUM, COLOSSEUM_TARGET))
        return restore(accountId, StoredTypedAutomationAction(entryId, UUID.randomUUID().toString(), StoredTypedActionPayload.Colosseum(action.teamIds)))
    }

    fun restore(accountId: Long, stored: StoredTypedAutomationAction): ManagedAutomationAction {
        val payload = stored.payload as StoredTypedActionPayload.Colosseum
        return object : ManagedAutomationAction {
            override val storedAction = stored
            override val descriptor = describe()
            private var current: ColosseumAutomationObservation? = null
            private var receipt: AutomationDirectResponse.Colosseum? = null
            override val directResponse: AutomationDirectResponse? get() = receipt

            override fun validateBeforeSubmission() {
                val state = recovery.execute(accountId) { service.loadForAutomation(accountId, clock.now()) }
                if (!state.complete) throw AutomationPreSubmitObservationIncompleteException("콜로세움 관측이 불완전합니다.")
                if (!state.response.challengeAvailable || state.response.selectedTeam.sorted() != payload.teamIds.sorted()) {
                    throw AutomationActionPreconditionChangedException("콜로세움 도전 조건 또는 저장 팀이 변경되었습니다.")
                }
                current = state
            }

            override fun execute(): TypedAutomationExecution {
                val observed = current ?: throw AutomationActionPreconditionChangedException("콜로세움 사전 검증이 필요합니다.")
                val response = try {
                    recovery.execute(accountId) { service.challengeForAutomation(accountId, observed, payload.teamIds) }
                } catch (error: Exception) {
                    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                    causes.filterIsInstance<HofAutomationDeferredException>().firstOrNull()?.let { throw it }
                    if (causes.any { it is ObservedTownActionPreconditionChangedException || it is AccountHofObservationInvalidatedException } || causes.filterIsInstance<ApiException>().any { it.errorCode == ErrorCode.INVALID_REQUEST }) {
                        throw AutomationActionPreconditionChangedException("콜로세움 실행 조건이 변경되었습니다.", error)
                    }
                    if (causes.any { it is IOException } || causes.filterIsInstance<ApiException>().any { it.errorCode == ErrorCode.HOF_REQUEST_FAILED }) {
                        throw AmbiguousAutomationSubmissionException("콜로세움 전투 결과를 확인하지 못해 재전송하지 않습니다.", error)
                    }
                    throw error
                }
                return restoreDirectResponse(AutomationDirectResponse.Colosseum(response.battleResult))
            }

            override fun restoreDirectResponse(response: AutomationDirectResponse): TypedAutomationExecution.ActionCompleted {
                require(response is AutomationDirectResponse.Colosseum)
                receipt = response
                val result = response.result
                val terminal = result != null && (result.turns ?: 0) > 0 && result.teams.size == 2 &&
                    result.teams.all { it.hp != null && it.survivors != null && it.totalDamage != null }
                val summary = "ColosseumBattleResponse|terminal=$terminal|turns=${result?.turns}"
                return TypedAutomationExecution.ActionCompleted(ColosseumObservedState(ProductionEvidenceShapes.fingerprint(summary), terminal),
                    ProductionEvidenceShapes.COLOSSEUM_RESPONSE, summary)
            }

            override fun applyPolicyAcceptedExecution(execution: TypedAutomationExecution): TypedAutomationExecution {
                work.completeColosseumCycle(accountId, stored.entryId, stored.executionIdentity)
                return execution
            }

            override fun applyLegacyExecution(execution: TypedAutomationExecution): TypedAutomationExecution {
                if ((execution as? TypedAutomationExecution.ActionCompleted)?.observedState?.let { it as? ColosseumObservedState }?.terminalResult != true) {
                    throw AmbiguousAutomationSubmissionException("콜로세움의 직접 전투 결과를 확인하지 못했습니다.")
                }
                return applyPolicyAcceptedExecution(execution)
            }

            override fun reconcile(): AmbiguousActionResolution {
                val latest = recovery.execute(accountId) { service.loadForAutomation(accountId, clock.now()) }
                if (latest.complete && (latest.cooldownUntil?.let { it > clock.now() } == true || latest.response.selectedTeam.sorted() != payload.teamIds.sorted())) {
                    work.completeColosseumCycle(accountId, stored.entryId, stored.executionIdentity)
                    return AmbiguousActionResolution.Superseded("콜로세움 최신 조건에서 새로 판단합니다. 이전 전투 결과는 귀속하지 않습니다.")
                }
                return AmbiguousActionResolution.VerifyLater(clock.now().plusSeconds(10), "콜로세움 직접 전투 결과를 확인하지 못했습니다.")
            }
        }
    }
}
