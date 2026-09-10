package app.spammy.hof.push.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.dto.DevicePushTargetResponse
import app.spammy.hof.push.dto.RegisterAndroidPushTargetRequest
import app.spammy.hof.push.entity.DevicePushTargetEntity
import app.spammy.hof.push.repository.DevicePushTargetQueryRepository
import app.spammy.hof.push.repository.DevicePushTargetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DevicePushTargetService(
    private val accountQueryRepository: AccountQueryRepository,
    private val repository: DevicePushTargetRepository,
    private val queryRepository: DevicePushTargetQueryRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun register(
        accountId: Long,
        request: RegisterAndroidPushTargetRequest,
    ): DevicePushTargetResponse {
        val installationId = request.installationId.trim()
        val token = request.nativeToken.trim()
        val now = timeProvider.now()
        val target = queryRepository.findOwnedByInstallation(accountId, installationId) ?: run {
            val account = accountQueryRepository.findById(accountId)
                ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
            DevicePushTargetEntity(
                account = account,
                installationId = installationId,
                targetValue = token,
                active = true,
                lastSeenAt = now,
                createdAt = now,
            )
        }
        target.targetValue = token
        target.active = true
        target.lastSeenAt = now
        return repository.save(target).toResponse()
    }

    @Transactional(readOnly = true)
    fun findAll(accountId: Long): List<DevicePushTargetResponse> =
        queryRepository.findActiveByAccountId(accountId).map { it.toResponse() }

    @Transactional
    fun deactivate(accountId: Long, targetId: Long) {
        val target = queryRepository.findOwnedByIdForUpdate(accountId, targetId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Android 알림 기기를 찾지 못했습니다.")
        target.active = false
        target.lastSeenAt = timeProvider.now()
    }

    /** 앱 재시작 뒤 메모리 target ID가 없어도 SecureStore의 안정적인 설치 ID로 현재 기기만 끈다. */
    @Transactional
    fun deactivateByInstallation(accountId: Long, installationId: String) {
        val target = queryRepository.findOwnedByInstallation(accountId, installationId.trim()) ?: return
        target.active = false
        target.lastSeenAt = timeProvider.now()
    }

    /** false이면 전송 중 바뀐 활성 token으로 같은 알림을 재시도해야 한다. */
    @Transactional
    fun deactivateRejectedToken(target: DevicePushTargetEntity): Boolean {
        val current = queryRepository.findOwnedByIdForUpdate(target.account.id, target.id) ?: return true
        // 원격 전송 중 갱신된 새 token을 이전 token의 늦은 실패로 폐기하지 않는다.
        if (current.targetValue != target.targetValue) return !current.active
        current.active = false
        current.lastSeenAt = timeProvider.now()
        return true
    }

    private fun DevicePushTargetEntity.toResponse() = DevicePushTargetResponse(
        id = id,
        platform = platform,
        installationId = installationId,
        active = active,
        lastSeenAt = lastSeenAt.toString(),
    )
}
