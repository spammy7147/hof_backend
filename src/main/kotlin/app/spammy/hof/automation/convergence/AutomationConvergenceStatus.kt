package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class AutomationConvergenceStatusResponse(
    val battleGate: AutomationBattleGateStatusResponse?,
    val items: List<AutomationConvergenceItemResponse>,
)

data class AutomationBattleGateStatusResponse(
    val challengeId: Long?,
    val reason: String,
    val openedAt: Instant,
    val impactScope: String = "모든 전투 자동화",
    val releaseCondition: String = "캡차 완료 후 최신 상태에서 다시 판단",
)

data class AutomationConvergenceItemResponse(
    val attemptId: Long,
    val entryId: Long?,
    val actionKind: AutomationActionKind,
    val scopeKind: AutomationIsolationScopeKind,
    val scopeKey: String,
    val result: ActionConvergenceResult,
    val successfulObservationCount: Int,
    val nextProbeAt: Instant?,
    val reasonCode: String?,
    val evidenceCaseId: String?,
    val impactScope: String,
    val releaseCondition: String,
    val canAllowFreshDecision: Boolean,
)

fun interface AutomationConvergenceStatusReader {
    fun read(accountId: Long): AutomationConvergenceStatusResponse
}

@Service
class JpaAutomationConvergenceStatusReader(
    private val entityManager: EntityManager,
) : AutomationConvergenceStatusReader {
    @Transactional(readOnly = true)
    override fun read(accountId: Long): AutomationConvergenceStatusResponse {
        val gate = entityManager.find(AccountBattleGateEntity::class.java, accountId)
            ?.takeIf { it.resolvedAt == null }
            ?.let {
                AutomationBattleGateStatusResponse(
                    challengeId = it.challengeId,
                    reason = it.reason,
                    openedAt = it.openedAt,
                )
            }
        val convergences = entityManager.createQuery(
            """
            select convergence from ActionConvergenceEntity convergence
            join fetch convergence.attempt attempt
            left join fetch attempt.entry
            where convergence.accountId = :accountId
              and (
                convergence.activeMarker = 1
                or (
                  convergence.result in :heldResults
                  and convergence.suppressionReleasedAt is null
                )
              )
            order by convergence.updatedAt desc, convergence.id desc
            """.trimIndent(),
            ActionConvergenceEntity::class.java,
        ).setParameter("accountId", accountId)
            .setParameter(
                "heldResults",
                setOf(ActionConvergenceResult.HELD, ActionConvergenceResult.RESULT_UNOBSERVED),
            )
            .resultList
        return AutomationConvergenceStatusResponse(
            battleGate = gate,
            items = convergences.map { convergence ->
                val result = convergence.result ?: ActionConvergenceResult.PENDING
                AutomationConvergenceItemResponse(
                    attemptId = requireNotNull(convergence.attempt.id),
                    entryId = convergence.attempt.entry?.id,
                    actionKind = convergence.attempt.actionKind,
                    scopeKind = convergence.scopeKind,
                    scopeKey = convergence.scopeKey,
                    result = result,
                    successfulObservationCount = convergence.successfulObservationCount,
                    nextProbeAt = convergence.nextProbeAt,
                    reasonCode = convergence.reasonCode,
                    evidenceCaseId = convergence.evidenceCaseId,
                    impactScope = convergence.scopeKind.impactLabel(convergence.scopeKey),
                    releaseCondition = result.releaseCondition(),
                    canAllowFreshDecision = result in setOf(
                        ActionConvergenceResult.HELD,
                        ActionConvergenceResult.RESULT_UNOBSERVED,
                    ),
                )
            },
        )
    }

    private fun AutomationIsolationScopeKind.impactLabel(key: String): String = when (this) {
        AutomationIsolationScopeKind.QUEST_TARGET -> "퀘스트 대상 $key"
        AutomationIsolationScopeKind.HOME_TARGET -> "자택 퀘스트 대상 $key"
        AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE -> "공유 전투 쿨타임 $key"
        AutomationIsolationScopeKind.UNION_ENTRY -> "유니온 자동화 $key"
        AutomationIsolationScopeKind.FISHING_ENTRY -> "낚시 자동화 $key"
        AutomationIsolationScopeKind.RAID_ENTRY -> "레이드 사이클 $key"
    }

    private fun ActionConvergenceResult.releaseCondition(): String = when (this) {
        ActionConvergenceResult.PENDING -> "최대 5회 또는 2분까지 읽기 전용으로 재확인"
        ActionConvergenceResult.HELD -> "상태 변경 또는 사용자의 새 행동 판단 허용"
        ActionConvergenceResult.RESULT_UNOBSERVED -> "상태 변경 또는 사용자의 새 행동 판단 허용"
        else -> "종료됨"
    }
}

@Service
class AutomationConvergenceCommandService(
    private val module: AutomationActionConvergenceModule,
    private val statusReader: AutomationConvergenceStatusReader,
    private val outbox: AutomationOutboxService,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun allowFreshDecision(accountId: Long, attemptId: Long): AutomationConvergenceStatusResponse {
        if (!module.allowFreshDecision(accountId, attemptId, timeProvider.now())) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "새 판단을 허용할 보류 항목을 찾지 못했습니다.")
        }
        outbox.enqueue(accountId, "TYPED_CONVERGENCE_USER_ALLOWED")
        return statusReader.read(accountId)
    }
}

@RestController
@RequestMapping("/api/automation/unified/convergence")
class AutomationConvergenceController(
    private val statusReader: AutomationConvergenceStatusReader,
    private val commandService: AutomationConvergenceCommandService,
) {
    /** 원격 행동을 실행하지 않는 순수 상태 새로고침이다. */
    @GetMapping
    fun get(@CurrentAccountId accountId: Long): AutomationConvergenceStatusResponse =
        statusReader.read(accountId)

    /** 저장 요청을 재생하지 않고 지정 보류만 닫은 뒤 fresh selection을 깨운다. */
    @PostMapping("/{attemptId}/allow-fresh-decision")
    fun allowFreshDecision(
        @CurrentAccountId accountId: Long,
        @PathVariable attemptId: Long,
    ): AutomationConvergenceStatusResponse = commandService.allowFreshDecision(accountId, attemptId)
}
