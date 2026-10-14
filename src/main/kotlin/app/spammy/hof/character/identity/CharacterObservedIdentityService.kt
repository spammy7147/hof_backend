package app.spammy.hof.character.identity

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterSectionSyncStateCommandRepository
import app.spammy.hof.character.repository.CharacterSyncJobQueryRepository
import app.spammy.hof.character.service.CharacterDeepSyncResponse
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/** 명시적 동기화의 저장 패턴 수집·원본 복구가 끝난 경우에만 외부 ID 교체를 확인한다. */
@Service
class CharacterObservedIdentityService(
    private val fence: AccountHofMutationFence,
    private val transactions: CharacterObservedIdentityTransaction,
    private val snapshots: CharacterSnapshotSynchronizer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun canVerify(accountId: Long, characterId: Long): Boolean = transactions.canVerify(accountId, characterId)

    fun completeOperation(accountId: Long, operationId: Long, resultPayload: String, finishedAt: Instant) = fence.execute(accountId) {
        refreshLinked(accountId, transactions.completeAndResolve(accountId, operationId, resultPayload, finishedAt))
    }

    private fun refreshLinked(accountId: Long, linkedId: Long?): Long? {
        if (linkedId == null) return null
        // 연결 자체는 이미 확정됐다. 후속 GET 실패가 완료된 원본 복구를 실패로 바꾸지는 않는다.
        runCatching { snapshots.refresh(accountId, linkedId) }.onFailure {
            log.warn("Linked character needs snapshot refresh accountId={} characterId={}", accountId, linkedId)
        }
        return linkedId
    }
}

@Service
class CharacterObservedIdentityTransaction(
    private val accounts: AccountQueryRepository,
    private val characters: CharacterQueryRepository,
    private val identities: CharacterIdentityQueryRepository,
    private val operations: CharacterOperationJobQueryRepository,
    private val operationCommands: CharacterOperationJobCommandRepository,
    private val sectionStates: CharacterSectionSyncStateCommandRepository,
    private val syncJobs: CharacterSyncJobQueryRepository,
    private val presets: PartyPresetQueryRepository,
    private val lifecycle: CharacterLifecycleTransaction,
    private val mapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun canVerify(accountId: Long, characterId: Long): Boolean {
        val observed = characters.findByAccountIdAndId(accountId, characterId) ?: return false
        val previous = candidate(accountId, observed) ?: return false
        return completedPatternEvidence(accountId, previous) != null
    }

    /** 완료 공개와 ID 교체를 같은 commit으로 묶어 임시 ID로 완료가 먼저 조회되지 않게 한다. */
    @Transactional
    fun completeAndResolve(accountId: Long, operationId: Long, resultPayload: String, finishedAt: Instant): Long? {
        if (accounts.findByIdForUpdate(accountId) == null) return null
        val job = operations.findByAccountIdAndId(accountId, operationId) ?: return null
        job.status = CharacterOperationStatus.COMPLETED
        job.resultPayload = resultPayload
        job.updatedAt = finishedAt
        job.finishedAt = finishedAt
        return resolveCompleted(accountId, operationId)
    }

    @Transactional
    fun resolveCompleted(accountId: Long, operationId: Long): Long? {
        if (accounts.findByIdForUpdate(accountId) == null) return null
        val operation = operations.findByAccountIdAndId(accountId, operationId) ?: return null
        if (operation.operationType != CharacterOperationType.DEEP_SYNC || !operation.automationReleased) return null
        val observed = characters.findByAccountIdAndIdForUpdate(accountId, operation.targetCharacterId) ?: return null
        val previous = candidate(accountId, observed) ?: return null
        val originalEvidence = completedPatternEvidence(accountId, previous) ?: return null
        val newEvidence = completedPatternEvidence(accountId, observed, operationId) ?: return null
        if (originalEvidence != newEvidence) return null
        if (operations.findConflictingJob(accountId) != null || presets.hasCharacterReference(observed.id)) return null
        val observedJobs = operations.findByCharacter(accountId, observed.id)
        // 프리셋이나 설정 가져오기 등에서 이미 독립 기록으로 사용한 대상은 직접 지정한다.
        if (observedJobs.any { it.operationType != CharacterOperationType.DEEP_SYNC || it.sourceCharacterId != null }) return null

        observedJobs.forEach { job ->
            job.targetCharacterId = previous.id
            job.resultPayload?.let { payload ->
                val result = mapper.readValue(payload, CharacterDeepSyncResponse::class.java)
                job.resultPayload = mapper.writeValueAsString(result.copy(characterId = previous.id))
            }
            syncJobs.findByCurrentOperation(job.id).forEach { parent ->
                parent.deepSyncTargets?.let { payload ->
                    val targets = mapper.readValue(payload, object : TypeReference<List<Long>>() {})
                    parent.deepSyncTargets = mapper.writeValueAsString(targets.map { if (it == observed.id) previous.id else it })
                }
            }
        }
        // target FK의 CASCADE가 수집 작업·복원 원본·전체 동기화 참조를 삭제하기 전에 소유자를 옮긴다.
        operationCommands.flush()
        lifecycle.link(accountId, previous.id, observed.hofCharacterId, CharacterHofIdLinkReason.KNOCKBACK,
            userConfirmed = false, provisionalCharacterId = observed.id)
        previous.detailSyncedAt = null
        // 성공 시각은 DB의 성공 계약을 만족한 채 남겨야 한다. 새 ID의 상세는 새 관측으로 다시 생성한다.
        sectionStates.deleteAll(characters.findSectionStates(previous.id))
        return previous.id
    }

    private fun candidate(accountId: Long, observed: CharacterEntity): CharacterEntity? {
        if (observed.lifecycle != CharacterLifecycle.ACTIVE || observed.name.isBlank() || observed.name == "(이름없음)" ||
            observed.job.isBlank() || observed.level == null) return null
        val roster = characters.findAllByAccountId(accountId)
        if (roster.count { it.lifecycle == CharacterLifecycle.ACTIVE && it.name == observed.name } != 1) return null
        val previous = roster.filter { it.lifecycle == CharacterLifecycle.MISSING && it.name == observed.name }.singleOrNull()
            ?: return null
        if (previous.job != observed.job || previous.level != observed.level) return null
        val history = identities.findOpenHistoryEvidence(observed.id) ?: return null
        if (history.linkReason != CharacterHofIdLinkReason.INITIAL_SYNC || identities.countHistory(observed.id) != 1L) return null
        val missingSince = previous.missingSince ?: return null
        if (history.validFrom.isBefore(missingSince)) return null
        return previous
    }

    /** 최신 깊은 동기화가 실패·미복원 상태라면 부분 archive를 동일성 증거로 쓰지 않는다. */
    private fun completedPatternEvidence(accountId: Long, character: CharacterEntity, expectedOperationId: Long? = null): List<SavedPatternEvidence>? {
        val job = operations.findLatestSync(accountId, character.id) ?: return null
        if (expectedOperationId != null && job.id != expectedOperationId) return null
        if (job.status != CharacterOperationStatus.COMPLETED || job.recoveryStatus != CharacterRecoveryStatus.RESTORED) return null
        if (character.lifecycle == CharacterLifecycle.MISSING &&
            (job.finishedAt == null || character.missingSince == null || job.finishedAt!!.isAfter(character.missingSince))) return null
        val collection = operations.findCollectionResult(job.id) ?: return null
        if (!collection.first || collection.second != null || operations.findOriginalHofCharacterId(job.id) != character.hofCharacterId) return null
        val slots = characters.findPatternIdentityRows(character.id)
        if (slots.none { it.canLoad }) return null
        return slots.groupBy { it.slotCode }.toSortedMap().values.map { rows ->
            val slot = rows.first()
            if (!slot.canLoad) return@map SavedPatternEvidence(slot.slotCode, false, emptyList(), null, null)
            if (rows.any { it.rowIndex == null } || rows.map { it.rowIndex }.distinct().size != rows.size || slot.selectedPosition == null || slot.guardValue == null) return null
            SavedPatternEvidence(slot.slotCode, true, rows.map {
                SavedPatternRowEvidence(it.rowIndex ?: return null, it.judge ?: return null, it.quantity ?: return null, it.skill ?: return null)
            }, slot.selectedPosition, slot.guardValue)
        }
    }
}

private data class SavedPatternEvidence(
    val slot: String,
    val canLoad: Boolean,
    val rows: List<SavedPatternRowEvidence>,
    val position: String?,
    val guard: String?,
)

private data class SavedPatternRowEvidence(val index: Int, val judge: String, val quantity: String, val skill: String)
