package app.spammy.hof.party.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetMemberRequest
import app.spammy.hof.party.dto.PartyPresetMemberResponse
import app.spammy.hof.party.dto.PartyPresetResponse
import app.spammy.hof.party.dto.ReorderPartyPresetsRequest
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetMemberCommandRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.repository.PartyPresetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * 캐릭터 탭의 5인 파티 프리셋을 관계형 부모·슬롯 행으로 저장하고 API 배열로 조립한다.
 *
 * 모든 참조를 먼저 일괄 검증한 다음 쓰기를 시작한다. 따라서 잘못된 캐릭터나 패턴이 포함된 수정
 * 요청은 기존 슬롯을 삭제하지 않으며, 정상 요청은 부모와 정확히 5개 자식을 한 트랜잭션에 남긴다.
 */
class PartyPresetService(
    private val accountQueryRepository: AccountQueryRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val presetRepository: PartyPresetRepository,
    private val memberRepository: PartyPresetMemberCommandRepository,
    private val presetQueryRepository: PartyPresetQueryRepository,
    private val timeProvider: TimeProvider,
) {
    /**
     * 부모 목록과 전체 슬롯 목록을 각각 한 번씩 조회해 최근 수정순 응답을 조립한다.
     */
    @Transactional(readOnly = true)
    fun findAll(accountId: Long): List<PartyPresetResponse> {
        val presets = presetQueryRepository.findAllByAccountId(accountId)
        if (presets.isEmpty()) return emptyList()

        val membersByPresetId = presetQueryRepository
            .findMembersByPresetIds(presets.map { preset -> preset.id })
            .groupBy { member -> member.preset.id }
        return presets.map { preset ->
            preset.toResponse(membersByPresetId[preset.id].orEmpty())
        }
    }

    /**
     * 계정과 모든 슬롯 참조를 검증한 뒤 부모를 먼저 저장하고 0~4 자식 행을 일괄 저장한다.
     */
    @Transactional
    fun create(
        accountId: Long,
        request: CreatePartyPresetRequest,
    ): PartyPresetResponse {
        val account = lockAccountForMutation(accountId)
        val name = normalizeName(request.name)
        val validatedMembers = validateMembers(accountId, request.members)
        val now = timeProvider.now()
        presetQueryRepository.findAllByAccountId(accountId).forEach { existing ->
            existing.displayOrder += 1
        }
        val preset = presetRepository.save(
            PartyPresetEntity(
                account = account,
                name = name,
                displayOrder = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val savedMembers = memberRepository.saveAll(validatedMembers.toEntities(preset))
        return preset.toResponse(savedMembers)
    }

    /** 계정의 전체 프리셋 집합을 검증한 뒤 요청 배열 순서로 표시 순서를 정규화한다. */
    @Transactional
    fun reorder(
        accountId: Long,
        request: ReorderPartyPresetsRequest,
    ): List<PartyPresetResponse> {
        lockAccountForMutation(accountId)
        val presets = presetQueryRepository.findAllByAccountId(accountId)
        val requestedIds = request.presetIds
        val ownedIds = presets.map { preset -> preset.id }.toSet()
        if (requestedIds.distinct().size != requestedIds.size || requestedIds.toSet() != ownedIds) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 계정의 모든 프리셋을 중복 없이 지정해야 합니다.")
        }

        val presetsById = presets.associateBy { preset -> preset.id }
        requestedIds.forEachIndexed { displayOrder, presetId ->
            presetsById.getValue(presetId).displayOrder = displayOrder
        }
        presetRepository.flush()
        return findAll(accountId)
    }

    /**
     * 새 구성을 전부 검증한 뒤 기존 자식을 flush하고 같은 복합 키 0~4로 교체한다.
     */
    @Transactional
    fun update(
        accountId: Long,
        presetId: Long,
        request: UpdatePartyPresetRequest,
    ): PartyPresetResponse {
        lockAccountForMutation(accountId)
        val preset = findOwnedPreset(accountId = accountId, presetId = presetId)
        val name = normalizeName(request.name)
        val validatedMembers = validateMembers(accountId, request.members)
        val existingMembers = presetQueryRepository.findMembersByPresetIds(listOf(preset.id))
        if (existingMembers.isNotEmpty()) {
            memberRepository.deleteAll(existingMembers)
            memberRepository.flush()
        }

        val replacements = memberRepository.saveAll(validatedMembers.toEntities(preset))
        preset.name = name
        preset.updatedAt = timeProvider.now()
        return preset.toResponse(replacements)
    }

    /**
     * 계정 row를 잠가 기본 프리셋이 없는 경우까지 계정 단위로 직렬화한 뒤 기본 상태를 교체한다.
     *
     * 기존 marker 해제를 먼저 flush하여 `(account_id, primary_marker)` unique key가 새 기본 marker와
     * 일시적으로 충돌하지 않게 한다.
     */
    @Transactional
    fun makePrimary(
        accountId: Long,
        presetId: Long,
    ): PartyPresetResponse {
        lockAccountForMutation(accountId)
        val selected = findOwnedPreset(accountId = accountId, presetId = presetId)
        val previous = presetQueryRepository.findPrimaryByAccountIdForUpdate(accountId)
        if (previous != null && previous.id != selected.id) {
            previous.clearPrimary()
            presetRepository.flush()
        }
        selected.markPrimary()
        selected.updatedAt = timeProvider.now()
        val members = presetQueryRepository.findMembersByPresetIds(listOf(selected.id))
        return selected.toResponse(members)
    }

    /**
     * FK cascade에만 의존하지 않고 자식 삭제를 먼저 flush한 뒤 부모를 삭제한다.
     */
    @Transactional
    fun delete(
        accountId: Long,
        presetId: Long,
    ) {
        lockAccountForMutation(accountId)
        val preset = findOwnedPreset(accountId = accountId, presetId = presetId)
        val members = presetQueryRepository.findMembersByPresetIds(listOf(preset.id))
        if (members.isNotEmpty()) {
            memberRepository.deleteAll(members)
            memberRepository.flush()
        }
        presetRepository.delete(preset)
        presetRepository.flush()
    }

    /**
     * 모든 계정별 프리셋 쓰기가 공유하는 잠금 경계다.
     *
     * 호출자는 이 잠금을 얻은 뒤에만 프리셋 부모나 슬롯을 조회·변경해야 stale entity 갱신과 기본
     * marker 교체가 서로 직렬화된다.
     */
    private fun lockAccountForMutation(accountId: Long) =
        accountQueryRepository.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")

    /**
     * 계정 ID와 프리셋 ID를 QueryDSL 한 조건으로 조회해 소유권 정보 노출을 막는다.
     */
    private fun findOwnedPreset(
        accountId: Long,
        presetId: Long,
    ): PartyPresetEntity =
        presetQueryRepository.findOwnedByAccountIdAndId(accountId = accountId, presetId = presetId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "파티 프리셋을 찾지 못했습니다.")

    /**
     * 프리셋 이름을 검증하고 앞뒤 공백을 제거한다.
     */
    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "프리셋 이름을 입력해야 합니다.")
        }
        return trimmed
    }

    /**
     * 슬롯 집합과 nullable 조합을 검증한 뒤 캐릭터와 패턴 FK 대상 entity를 bulk query로 해석한다.
     *
     * 요청의 `characterId`는 DB PK가 아니라 HOF 원본 ID다. 계정과 HOF ID를 함께 조회한 결과에 없는
     * 값은 다른 계정 소유 여부와 무관하게 RESOURCE_NOT_FOUND로 처리한다. `patternSlot` 정수는 기존
     * 저장 규약대로 문자열 [CharacterPatternSlotEntity.slotCode]로 바꿔 선택 캐릭터의 슬롯인지 확인한다.
     */
    private fun validateMembers(
        accountId: Long,
        members: List<PartyPresetMemberRequest>,
    ): List<ValidatedMember> {
        if (members.size != PARTY_SIZE) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "프리셋은 5개 슬롯을 포함해야 합니다.")
        }
        if (members.map { member -> member.slotIndex }.toSet() != VALID_SLOT_INDEXES) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "프리셋 슬롯은 0번부터 4번까지 한 번씩 포함해야 합니다.")
        }

        val normalized = members
            .sortedBy { member -> member.slotIndex }
            .map { member ->
                NormalizedMember(
                    slotIndex = member.slotIndex,
                    characterId = member.characterId?.trim()?.ifBlank { null },
                    patternSlot = member.patternSlot,
                )
            }
        normalized.forEach { member ->
            if (member.characterId == null && member.patternSlot != null) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "비어 있는 슬롯에는 패턴을 지정할 수 없습니다.")
            }
            if (member.patternSlot != null && member.patternSlot < 0) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "패턴 슬롯은 0 이상이어야 합니다.")
            }
        }

        val requestedCharacterIds = normalized.mapNotNull { member -> member.characterId }.toSet()
        val charactersByHofId = characterQueryRepository
            .findByAccountIdAndHofCharacterIds(accountId, requestedCharacterIds)
            .associateBy { character -> character.hofCharacterId }
        if (charactersByHofId.keys != requestedCharacterIds) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
        }

        val patternCharacterIds = normalized
            .filter { member -> member.patternSlot != null }
            .map { member -> charactersByHofId.getValue(requireNotNull(member.characterId)).id }
            .toSet()
        val patternsByCharacterAndCode = characterQueryRepository
            .findPatternSlotsByCharacterIds(patternCharacterIds)
            .associateBy { pattern -> pattern.character.id to pattern.slotCode }

        return normalized.map { member ->
            val character = member.characterId?.let(charactersByHofId::getValue)
            val pattern = member.patternSlot?.let { slot ->
                patternsByCharacterAndCode[character!!.id to slot.toString()]
                    ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터 패턴 슬롯을 찾지 못했습니다.")
            }
            ValidatedMember(
                slotIndex = member.slotIndex,
                character = character,
                patternSlot = pattern,
            )
        }
    }

    /** 검증된 FK 대상을 한 부모의 0~4 슬롯 entity로 변환한다. */
    private fun List<ValidatedMember>.toEntities(preset: PartyPresetEntity): List<PartyPresetMemberEntity> =
        map { member ->
            PartyPresetMemberEntity(
                preset = preset,
                slotIndex = member.slotIndex,
                character = member.character,
                patternSlot = member.patternSlot,
            )
        }

    /** 부모와 정렬된 슬롯 entity를 기존 API 응답 배열로 조립한다. */
    private fun PartyPresetEntity.toResponse(members: List<PartyPresetMemberEntity>): PartyPresetResponse =
        PartyPresetResponse(
            id = id,
            accountId = account.id,
            name = name,
            displayOrder = displayOrder,
            isPrimary = isPrimary,
            members = members
                .sortedBy { member -> member.slotIndex }
                .map { member -> member.toResponse() },
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    /** DB의 HOF 캐릭터 ID와 문자열 슬롯 코드를 기존 nullable API 필드로 되돌린다. */
    private fun PartyPresetMemberEntity.toResponse(): PartyPresetMemberResponse =
        PartyPresetMemberResponse(
            slotIndex = slotIndex,
            characterId = character?.hofCharacterId,
            patternSlot = patternSlot?.slotCode?.toIntOrNull(),
        )

    private data class NormalizedMember(
        val slotIndex: Int,
        val characterId: String?,
        val patternSlot: Int?,
    )

    private data class ValidatedMember(
        val slotIndex: Int,
        val character: CharacterEntity?,
        val patternSlot: CharacterPatternSlotEntity?,
    )

    private companion object {
        const val PARTY_SIZE = 5
        val VALID_SLOT_INDEXES = (0 until PARTY_SIZE).toSet()
    }
}
