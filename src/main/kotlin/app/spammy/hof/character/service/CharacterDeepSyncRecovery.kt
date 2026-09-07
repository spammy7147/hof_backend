package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.dto.CharacterRecoveryPreviewResponse
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.parser.CharacterPageParseResult
import java.util.UUID
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryEquipmentEntity
import app.spammy.hof.character.entity.CharacterRecoveryOriginalEntity
import app.spammy.hof.character.entity.CharacterRecoveryPatternEntity
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterRecoveryCommandRepository
import app.spammy.hof.character.repository.CharacterRecoveryQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofEquipment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CharacterDeepSyncRecovery(
    private val jobs: CharacterOperationJobQueryRepository,
    private val originals: CharacterRecoveryQueryRepository,
    private val commands: CharacterRecoveryCommandRepository,
    private val timeProvider: TimeProvider,
    private val accounts: AccountQueryRepository,
    private val automation: CharacterOperationAutomation,
) {
    @Transactional
    fun recordReview(jobId: Long, accountId: Long, page: CharacterPageParseResult): CharacterRecoveryPreviewResponse {
        requireAccountLock(accountId)
        val job = reviewableJob(jobId, accountId)
        val fingerprint = CharacterSyncObservation.fingerprint(CharacterRestoreState.capture(page))
        val now = timeProvider.now()
        val token = UUID.randomUUID().toString()
        job.recoveryReviewToken = token
        job.recoveryReviewFingerprint = fingerprint
        job.recoveryReviewedAt = now
        job.updatedAt = now
        return CharacterRecoveryPreviewResponse(jobId, job.targetCharacterId, token, now, now.plusSeconds(300),
            page.snapshot.id, page.snapshot.name, page.snapshot.actionPatterns, page.snapshot.equipment, page.snapshot.positionGuard)
    }

    @Transactional
    fun acceptReview(jobId: Long, accountId: Long, token: String, current: CharacterRestoreState?) {
        requireAccountLock(accountId)
        val job = jobs.findByAccountIdAndId(accountId, jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 작업을 찾지 못했습니다.")
        if (token != job.recoveryReviewToken) reviewConflict("현재 상태 확인 결과가 일치하지 않습니다. 다시 확인해 주세요.")
        if (job.recoveryStatus == CharacterRecoveryStatus.ACCEPTED) return
        reviewableJob(jobId, accountId)
        val now = timeProvider.now()
        if (job.recoveryReviewedAt?.plusSeconds(300)?.isAfter(now) != true) reviewConflict("현재 상태 확인 시간이 만료되었습니다. 다시 확인해 주세요.")
        if (current == null || CharacterSyncObservation.fingerprint(current) != job.recoveryReviewFingerprint) {
            reviewConflict("확인 후 원본 서버의 설정이 변경되었습니다. 현재 상태를 다시 확인해 주세요.")
        }
        automation.acceptCurrent(accountId, jobId)
        job.recoveryStatus = CharacterRecoveryStatus.ACCEPTED
        job.status = CharacterOperationStatus.STOPPED
        job.recoveryAcceptedAt = now
        job.finishedAt = now
        job.updatedAt = now
        // 기존 수집 실패 이유와 최초 원본은 진단을 위해 그대로 보존한다.
    }

    private fun requireAccountLock(accountId: Long) {
        accounts.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
    }

    private fun reviewableJob(jobId: Long, accountId: Long) =
        (jobs.findByAccountIdAndId(accountId, jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 작업을 찾지 못했습니다.")).also {
            if (it.operationType == CharacterOperationType.TRANSFER ||
                it.status !in setOf(CharacterOperationStatus.FAILED, CharacterOperationStatus.STOPPED) ||
                it.recoveryStatus !in setOf(CharacterRecoveryStatus.REQUIRED, CharacterRecoveryStatus.RESTORING, CharacterRecoveryStatus.UNAVAILABLE)) {
                reviewConflict("진행 중이거나 복구 확인이 필요하지 않은 작업입니다. 작업 상태를 다시 확인해 주세요.")
            }
        }

    private fun reviewConflict(message: String): Nothing = throw ApiException(ErrorCode.CHARACTER_RECOVERY_REQUIRED, message)

    @Transactional(readOnly = true)
    fun load(jobId: Long, accountId: Long, characterId: Long): CharacterDeepSyncCheckpoint? {
        val job = ownedJob(jobId, accountId, characterId)
        val original = originals.findByJobId(jobId)
        if (original == null) {
            check(job.recoveryStatus == CharacterRecoveryStatus.NOT_STARTED) { "작업의 복원 원본이 없습니다. 현재 상태 확인이 필요합니다." }
            return null
        }
        return CharacterDeepSyncCheckpoint(
            original.toState(), requireNotNull(job.recoveryStatus), original.collectionComplete,
            original.collectionError, original.restoreAttempts,
            CharacterSyncObservation(
                requireNotNull(original.observedEquipment), requireNotNull(original.observedPatterns),
                requireNotNull(original.observedConditions), requireNotNull(original.observedPosition),
                requireNotNull(original.observedGuard),
                original.observedConditionPrefixes?.split(',') ?: recoverConditionPrefixes(original, characterId),
            ),
            original.pendingChange?.let(CharacterSyncChange::valueOf),
            job.restoreAttemptLimit,
        )
    }

    /** transaction commit 이후에만 원격 변경이 허용된다. 최초 원본 row는 다시 쓰지 않는다. */
    @Transactional
    fun save(jobId: Long, accountId: Long, characterId: Long, checkpoint: CharacterDeepSyncCheckpoint) {
        val job = ownedJob(jobId, accountId, characterId)
        val existing = originals.findByJobId(jobId)
        val original = existing ?: run {
            check(job.recoveryStatus == CharacterRecoveryStatus.NOT_STARTED) { "복원 원본을 새로 캡처할 수 없는 작업입니다." }
            val state = checkpoint.original
            val row = CharacterRecoveryOriginalEntity(jobId, state.hofCharacterId, state.position, state.guard, timeProvider.now())
            row.patterns += state.patterns.map {
                CharacterRecoveryPatternEntity(original = row, rowIndex = it.index, judge = it.judge, quantity = it.quantity, skill = it.skill)
            }
            row.equipment += state.equipment.mapIndexed { index, it ->
                CharacterRecoveryEquipmentEntity(original = row, itemOrder = index, slot = it.slot, part = it.part,
                    name = it.name, iconUrl = it.iconUrl, description = it.description)
            }
            commands.save(row)
        }
        check(original.toState() == checkpoint.original) { "같은 작업의 최초 복원 원본을 변경할 수 없습니다." }
        original.collectionComplete = checkpoint.collectionComplete
        original.collectionError = checkpoint.collectionError
        original.restoreAttempts = checkpoint.restoreAttempts
        original.observedEquipment = checkpoint.observed.equipment
        original.observedPatterns = checkpoint.observed.patterns
        original.observedConditions = checkpoint.observed.conditions
        original.observedConditionPrefixes = checkpoint.observed.conditionPrefixes.takeIf { it.isNotEmpty() }?.joinToString(",")
        original.observedPosition = checkpoint.observed.position
        original.observedGuard = checkpoint.observed.guard
        original.pendingChange = checkpoint.pendingChange?.name
        job.recoveryStatus = checkpoint.status
        job.updatedAt = timeProvider.now()
    }

    private fun ownedJob(jobId: Long, accountId: Long, characterId: Long) =
        requireNotNull(jobs.findByAccountIdAndId(accountId, jobId)) { "캐릭터 작업을 찾지 못했습니다." }.also {
            check(it.targetCharacterId == characterId && it.operationType != CharacterOperationType.TRANSFER) {
                "복원 원본의 작업·계정·캐릭터가 일치하지 않습니다."
            }
        }

    /** V55 작업은 전체 지문과 정확히 일치하는 보존 자료가 있을 때만 행별 비교 정보를 보강한다. */
    private fun recoverConditionPrefixes(original: CharacterRecoveryOriginalEntity, characterId: Long): List<String> {
        val originalPrefixes = CharacterSyncObservation.conditionPrefixes(original.toState().patterns)
        if (originalPrefixes.lastOrNull() == original.observedConditions) return originalPrefixes
        return originals.findSavedPatternRows(characterId).groupBy { it.patternSlot.id }.values
            .asSequence().map { rows ->
                CharacterSyncObservation.conditionPrefixes(rows.map {
                    HofActionPatternRow(it.rowIndex, judge = it.judge, quantity = it.quantity, skill = it.skill)
                })
            }.firstOrNull { it.lastOrNull() == original.observedConditions } ?: emptyList()
    }

    private fun CharacterRecoveryOriginalEntity.toState() = CharacterRestoreState(
        hofCharacterId,
        patterns.sortedBy { it.rowIndex }.map { HofActionPatternRow(it.rowIndex, judge = it.judge, quantity = it.quantity, skill = it.skill) },
        equipment.sortedBy { it.itemOrder }.map { HofEquipment(it.slot, it.part, it.name, it.iconUrl, it.description) },
        position, guard,
    )
}
