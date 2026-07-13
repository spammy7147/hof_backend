package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.AutomationProfileMapRequest
import app.spammy.hof.automation.dto.AutomationProfileMapResponse
import app.spammy.hof.automation.dto.AutomationProfileResponse
import app.spammy.hof.automation.dto.CreateAutomationProfileRequest
import app.spammy.hof.automation.dto.UpdateAutomationProfileRequest
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.AutomationProfileMapEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationProfileMapCommandRepository
import app.spammy.hof.automation.repository.AutomationProfileQueryRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * 자동전투 프로필 부모와 실행 맵 행을 한 트랜잭션에서 저장하고 구조화된 API 응답으로 조립한다.
 *
 * 요청의 모든 맵과 nullable 프리셋 FK를 먼저 검증하므로 잘못된 수정 요청은 기존 맵 행을 건드리지
 * 않는다. 정상 수정은 기존 자식을 삭제·flush한 뒤 교체 행을 삽입해 유일 키 충돌을 피한다.
 */
class AutomationProfileService(
    private val accountQueryRepository: AccountQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val partyPresetQueryRepository: PartyPresetQueryRepository,
    private val profileRepository: AutomationProfileRepository,
    private val profileMapRepository: AutomationProfileMapCommandRepository,
    private val profileQueryRepository: AutomationProfileQueryRepository,
    private val jobQueryRepository: AutomationJobQueryRepository,
    private val timeProvider: TimeProvider,
) {
    /** 부모 목록과 전체 맵 목록을 각각 한 번씩 조회해 최근 수정순 응답을 조립한다. */
    @Transactional(readOnly = true)
    fun findAll(accountId: Long): List<AutomationProfileResponse> {
        val profiles = profileQueryRepository.findAllByAccountId(accountId)
        if (profiles.isEmpty()) return emptyList()

        val mapsByProfileId = profileQueryRepository
            .findMapsByProfileIds(profiles.map { profile -> profile.id })
            .groupBy { profileMap -> profileMap.profile.id }
        return profiles.map { profile ->
            profile.toResponse(mapsByProfileId[profile.id].orEmpty())
        }
    }

    /** 계정과 모든 FK를 검증한 뒤 부모와 맵 자식을 함께 생성한다. 빈 맵 목록은 편집 초기 상태로 허용한다. */
    @Transactional
    fun create(
        accountId: Long,
        request: CreateAutomationProfileRequest,
    ): AutomationProfileResponse {
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val name = normalizeName(request.name)
        val mode = normalizeMode(request.mode)
        val validatedMaps = validateMaps(accountId, request.maps)
        val now = timeProvider.now()
        val profile = profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = name,
                mode = mode,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val savedMaps = saveMaps(profile, validatedMaps)
        return profile.toResponse(savedMaps)
    }

    /** 새 값을 전부 검증한 다음 기존 맵 행을 flush하고 교체 행을 저장한다. */
    @Transactional
    fun update(
        accountId: Long,
        profileId: Long,
        request: UpdateAutomationProfileRequest,
    ): AutomationProfileResponse {
        val profile = findOwnedProfile(accountId = accountId, profileId = profileId)
        val name = normalizeName(request.name)
        val mode = normalizeMode(request.mode)
        val validatedMaps = validateMaps(accountId, request.maps)
        val existingMaps = profileQueryRepository.findMapsByProfileIds(listOf(profile.id))
        if (existingMaps.isNotEmpty()) {
            profileMapRepository.deleteAll(existingMaps)
            profileMapRepository.flush()
        }

        val replacements = saveMaps(profile, validatedMaps)
        profile.name = name
        profile.mode = mode
        profile.enabled = request.enabled
        profile.updatedAt = timeProvider.now()
        return profile.toResponse(replacements)
    }

    /** 자식 맵 행을 먼저 삭제·flush한 뒤 프로필 부모를 삭제한다. */
    @Transactional
    fun delete(
        accountId: Long,
        profileId: Long,
    ) {
        val profile = findOwnedProfile(accountId = accountId, profileId = profileId)
        if (jobQueryRepository.existsByProfileId(profile.id)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "실행 이력이 있는 자동전투 프로필은 삭제할 수 없습니다.")
        }
        val maps = profileQueryRepository.findMapsByProfileIds(listOf(profile.id))
        if (maps.isNotEmpty()) {
            profileMapRepository.deleteAll(maps)
            profileMapRepository.flush()
        }
        profileRepository.delete(profile)
        profileRepository.flush()
    }

    /** 계정 ID와 프로필 ID를 QueryDSL 한 조건으로 조회해 소유권 정보 노출을 막는다. */
    private fun findOwnedProfile(
        accountId: Long,
        profileId: Long,
    ): AutomationProfileEntity =
        profileQueryRepository.findOwnedByAccountIdAndId(accountId = accountId, profileId = profileId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동전투 프로필을 찾지 못했습니다.")

    /**
     * 맵 식별자와 실행 순서를 정규화하고 중복을 검사한 뒤 QueryDSL로 FK 대상을 해석한다.
     *
     * 프리셋 ID가 null이면 편집 중 미선택 상태로 보존한다. 값이 있으면 계정과 PK를 함께 조회해 다른
     * 계정 프리셋도 RESOURCE_NOT_FOUND로 처리하며, 같은 ID는 한 번만 조회한다.
     */
    private fun validateMaps(
        accountId: Long,
        maps: List<AutomationProfileMapRequest>,
    ): List<ValidatedProfileMap> {
        if (maps.size > MAX_PROFILE_MAPS) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 프로필에는 맵을 최대 100개까지 지정할 수 있습니다.")
        }
        val normalized = maps.map { request -> request.normalize() }
        if (normalized.map { map -> map.categoryId to map.mapCode }.toSet().size != normalized.size) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 프로필에 같은 맵을 두 번 지정할 수 없습니다.")
        }
        if (normalized.map { map -> map.executionOrder }.toSet().size != normalized.size) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 맵 실행 순서는 중복될 수 없습니다.")
        }

        val requestedMapIdentities = normalized.map { map -> map.categoryId to map.mapCode }.toSet()
        val battleMapsByIdentity = battleMapQueryRepository
            .findMapsByCategoryIdAndMapCodePairs(requestedMapIdentities)
            .associateBy { battleMap -> battleMap.categoryId to battleMap.mapCode }
        if (battleMapsByIdentity.keys != requestedMapIdentities) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "전투 맵을 찾지 못했습니다.")
        }

        val requestedPresetIds = normalized.mapNotNull { map -> map.partyPresetId }.toSet()
        val presetsById = partyPresetQueryRepository
            .findOwnedByAccountIdAndIds(accountId, requestedPresetIds)
            .associateBy { preset -> preset.id }
        if (presetsById.keys != requestedPresetIds) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "파티 프리셋을 찾지 못했습니다.")
        }

        return normalized.map { map ->
            ValidatedProfileMap(
                battleMap = battleMapsByIdentity.getValue(map.categoryId to map.mapCode),
                partyPreset = map.partyPresetId?.let(presetsById::getValue),
                executionOrder = map.executionOrder,
            )
        }
    }

    /** 공백 식별자와 음수 실행 순서를 쓰기 전에 INVALID_REQUEST로 거절한다. */
    private fun AutomationProfileMapRequest.normalize(): NormalizedProfileMap {
        val normalizedCategoryId = categoryId.trim()
        val normalizedMapCode = mapCode.trim()
        if (normalizedCategoryId.isBlank() || normalizedMapCode.isBlank()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 맵의 카테고리와 맵 코드를 입력해야 합니다.")
        }
        if (executionOrder < 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 맵 실행 순서는 0 이상이어야 합니다.")
        }
        return NormalizedProfileMap(
            categoryId = normalizedCategoryId,
            mapCode = normalizedMapCode,
            partyPresetId = partyPresetId,
            executionOrder = executionOrder,
        )
    }

    /** 검증된 FK 대상을 한 프로필의 순서 행으로 변환해 일괄 저장한다. */
    private fun saveMaps(
        profile: AutomationProfileEntity,
        maps: List<ValidatedProfileMap>,
    ): List<AutomationProfileMapEntity> {
        if (maps.isEmpty()) return emptyList()

        return profileMapRepository.saveAll(
            maps.map { map ->
                AutomationProfileMapEntity(
                    profile = profile,
                    battleMap = map.battleMap,
                    partyPreset = map.partyPreset,
                    executionOrder = map.executionOrder,
                )
            },
        )
    }

    /** 부모와 맵 행을 실행 순서, 맵 코드 순으로 정렬해 API 응답으로 조립한다. */
    private fun AutomationProfileEntity.toResponse(maps: List<AutomationProfileMapEntity>): AutomationProfileResponse =
        AutomationProfileResponse(
            id = id,
            accountId = account.id,
            name = name,
            mode = mode,
            maps = maps
                .sortedWith(compareBy(AutomationProfileMapEntity::executionOrder, { it.battleMap.mapCode }))
                .map { map -> map.toResponse() },
            enabled = enabled,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )

    /** 관계형 FK 행을 외부 정적 식별자와 nullable 프리셋 ID로 변환한다. */
    private fun AutomationProfileMapEntity.toResponse(): AutomationProfileMapResponse =
        AutomationProfileMapResponse(
            categoryId = battleMap.categoryId,
            mapCode = battleMap.mapCode,
            partyPresetId = partyPreset?.id,
            executionOrder = executionOrder,
        )

    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "자동전투 이름을 입력해야 합니다.")
        }
        return trimmed
    }

    private fun normalizeMode(mode: String): String {
        val normalized = mode.trim().uppercase()
        if (normalized !in SUPPORTED_MODES) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "지원하지 않는 자동전투 모드입니다.")
        }
        return normalized
    }

    private data class NormalizedProfileMap(
        val categoryId: String,
        val mapCode: String,
        val partyPresetId: Long?,
        val executionOrder: Int,
    )

    private data class ValidatedProfileMap(
        val battleMap: BattleMapEntity,
        val partyPreset: PartyPresetEntity?,
        val executionOrder: Int,
    )

    private companion object {
        const val MAX_PROFILE_MAPS = 100
        val SUPPORTED_MODES = setOf("TIME_BURN", "BASIC_ADVENTURE", "LIMITED_DUNGEON")
    }
}
