package app.spammy.hof.automation.service

import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationProfileQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
/**
 * 프로필에 연결된 자동화 job의 생성, 현재 상태 조회, 일시정지·재개·취소를 담당한다.
 *
 * 생성 시 계정과 프로필 PK를 QueryDSL 한 조건으로 검증하고 프로필의 계정 entity를 그대로 참조해
 * account/profile FK가 같은 소유자를 가리키게 한다. 상태 변경도 계정과 job PK를 함께 조회한다.
 */
class AutomationJobService(
    private val profileQueryRepository: AutomationProfileQueryRepository,
    private val automationJobRepository: AutomationJobRepository,
    private val automationJobQueryRepository: AutomationJobQueryRepository,
    transactionManager: PlatformTransactionManager,
    private val timeProvider: TimeProvider,
) {
    private val mutationTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /**
     * 실행 가능한 소유 프로필로 진행 단계 0의 PENDING job을 생성한다.
     *
     * 현재 배포 구조는 단일 JVM과 파일 DB이므로 전역 JVM lock으로 생성 요청을 직렬화한다. lock 안에서
     * [TransactionTemplate]을 실행해 활성 job 조회부터 INSERT commit까지 보호하며, commit이 끝난 뒤에만
     * 다음 생성 요청이 활성 상태를 다시 조회하게 한다. 다중 인스턴스로 확장할 때는 DB lock으로 대체해야 한다.
     */
    fun create(
        accountId: Long,
        request: CreateAutomationJobRequest,
    ): AutomationJobResponse =
        executeLockedMutation {
            createInTransaction(accountId, request)
        }

    private fun createInTransaction(
        accountId: Long,
        request: CreateAutomationJobRequest,
    ): AutomationJobResponse {
        val profile = profileQueryRepository.findOwnedByAccountIdAndId(accountId, request.profileId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동전투 프로필을 찾지 못했습니다.")
        if (!profile.enabled) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "비활성 자동전투 프로필은 실행할 수 없습니다.")
        }

        val profileMaps = profileQueryRepository.findMapsByProfileIds(listOf(profile.id))
        if (profileMaps.isEmpty()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 프로필에 실행할 맵이 없습니다.")
        }
        if (profileMaps.any { profileMap -> profileMap.partyPreset == null }) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "모든 자동전투 맵에 파티 프리셋을 지정해야 합니다.")
        }
        if (profileMaps.any { profileMap -> !profileMap.battleMap.enabled }) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "비활성 전투 맵이 포함된 자동전투 프로필은 실행할 수 없습니다.")
        }
        if (automationJobQueryRepository.findCurrentByAccountIdAndStatuses(accountId, ACTIVE_STATUSES) != null) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "이미 진행 중인 자동화 job이 있습니다.")
        }

        val now = timeProvider.now()
        return automationJobRepository.save(
            AutomationJobEntity(
                account = profile.account,
                profile = profile,
                status = STATUS_PENDING,
                currentStepIndex = 0,
                message = "생성됨. 실행 루프는 다음 단계에서 연결됩니다.",
                createdAt = now,
                startedAt = null,
                updatedAt = now,
                finishedAt = null,
            ),
        ).toResponse()
    }

    /** 활성 상태 중 최근 수정 시각과 ID가 가장 큰 job을 QueryDSL로 조회한다. */
    @Transactional(readOnly = true)
    fun findCurrent(accountId: Long): AutomationJobResponse? =
        automationJobQueryRepository.findCurrentByAccountIdAndStatuses(accountId, ACTIVE_STATUSES)
            ?.toResponse()

    /** RUNNING 또는 WAITING_CAPTCHA job만 잠금 트랜잭션에서 일시정지한다. */
    fun pause(
        accountId: Long,
        jobId: Long,
    ): AutomationJobResponse =
        executeLockedMutation {
            val job = findOwnedJob(accountId, jobId)
            requireStatus(job, PAUSABLE_STATUSES, "현재 상태에서는 자동화 job을 일시정지할 수 없습니다.")
            job.status = STATUS_PAUSED
            job.message = "일시정지됨"
            job.updatedAt = timeProvider.now()
            job.toResponse()
        }

    /** PAUSED job만 잠금 트랜잭션에서 RUNNING으로 되돌리고 최초 재개 시 startedAt을 기록한다. */
    fun resume(
        accountId: Long,
        jobId: Long,
    ): AutomationJobResponse =
        executeLockedMutation {
            val job = findOwnedJob(accountId, jobId)
            requireStatus(job, setOf(STATUS_PAUSED), "일시정지된 자동화 job만 재개할 수 있습니다.")
            val now = timeProvider.now()
            job.status = STATUS_RUNNING
            job.message = "재개 대기"
            if (job.startedAt == null) job.startedAt = now
            job.updatedAt = now
            job.toResponse()
        }

    /** 활성 비종료 상태의 job만 잠금 트랜잭션에서 취소하고 finishedAt을 기록한다. */
    fun cancel(
        accountId: Long,
        jobId: Long,
    ): AutomationJobResponse =
        executeLockedMutation {
            val job = findOwnedJob(accountId, jobId)
            requireStatus(job, ACTIVE_STATUSES, "종료된 자동화 job은 다시 취소할 수 없습니다.")
            val now = timeProvider.now()
            job.status = STATUS_CANCELLED
            job.message = "취소됨"
            job.updatedAt = now
            job.finishedAt = now
            job.toResponse()
        }

    /**
     * 단일 JVM의 모든 job read-modify-write를 직렬화하고 DB commit까지 lock을 유지한다.
     *
     * 상태 조회와 변경을 같은 REQUIRES_NEW 트랜잭션 안에서 수행하므로, 먼저 취소된 job을 뒤늦게 읽은
     * resume 요청이 이전 PAUSED 상태를 기준으로 RUNNING으로 덮어쓰는 경쟁을 막는다.
     */
    private fun <T> executeLockedMutation(block: () -> T): T =
        synchronized(JOB_MUTATION_LOCK) {
            mutationTransaction.execute { block() }
        }

    private fun requireStatus(
        job: AutomationJobEntity,
        allowedStatuses: Collection<String>,
        message: String,
    ) {
        if (job.status !in allowedStatuses) {
            throw ApiException(ErrorCode.INVALID_REQUEST, message)
        }
    }

    /** 계정과 job PK를 함께 조회해 다른 계정의 job 존재 여부를 노출하지 않는다. */
    private fun findOwnedJob(
        accountId: Long,
        jobId: Long,
    ): AutomationJobEntity =
        automationJobQueryRepository.findOwnedByAccountIdAndId(accountId, jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 job을 찾지 못했습니다.")

    private fun AutomationJobEntity.toResponse(): AutomationJobResponse =
        AutomationJobResponse(
            id = id,
            accountId = account.id,
            profileId = profile.id,
            status = status,
            currentStepIndex = currentStepIndex,
            message = message,
            createdAt = createdAt.toString(),
            startedAt = startedAt?.toString(),
            updatedAt = updatedAt.toString(),
            finishedAt = finishedAt?.toString(),
        )

    private companion object {
        val JOB_MUTATION_LOCK = Any()
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_WAITING_CAPTCHA = "WAITING_CAPTCHA"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_CANCELLED = "CANCELLED"
        val PAUSABLE_STATUSES = setOf(STATUS_RUNNING, STATUS_WAITING_CAPTCHA)
        val ACTIVE_STATUSES = setOf(STATUS_PENDING, STATUS_RUNNING, STATUS_WAITING_CAPTCHA, STATUS_PAUSED)
    }
}
