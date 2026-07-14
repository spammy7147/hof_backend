package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.AutomationModuleMapRequest
import app.spammy.hof.automation.dto.AutomationModuleMapResponse
import app.spammy.hof.automation.dto.AutomationModuleQuestRequest
import app.spammy.hof.automation.dto.AutomationModuleQuestResponse
import app.spammy.hof.automation.dto.AutomationModuleResponse
import app.spammy.hof.automation.dto.CreateAutomationModuleRequest
import app.spammy.hof.automation.dto.ReorderAutomationModulesRequest
import app.spammy.hof.automation.dto.UnifiedAutomationStatusResponse
import app.spammy.hof.automation.dto.UpdateAutomationModuleRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.AutomationModuleConfigRepository
import app.spammy.hof.automation.repository.AutomationModuleMapCommandRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestAggregate
import app.spammy.hof.automation.repository.AutomationModuleQuestCommandRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestMapCommandRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 사용자 구성형 통합 자동화의 모듈 설정과 실행 생명주기를 관리한다.
 *
 * 조회는 계정 소유권 조건을 포함한 QueryDSL repository에서만 수행하고, command repository는 검증이
 * 끝난 aggregate의 저장·삭제에만 사용한다. 설정 변경은 현재 job이나 action을 취소하지 않으며, 실행
 * 중인 job에는 다음 의사결정을 앞당기는 wakeup만 전달한다.
 */
@Service
class UnifiedAutomationService(
    private val accountQueryRepository: AccountQueryRepository,
    private val profileRepository: AutomationProfileRepository,
    private val moduleConfigRepository: AutomationModuleConfigRepository,
    private val moduleMapRepository: AutomationModuleMapCommandRepository,
    private val moduleQuestRepository: AutomationModuleQuestCommandRepository,
    private val moduleQuestMapRepository: AutomationModuleQuestMapCommandRepository,
    private val jobRepository: AutomationJobRepository,
    private val queryRepository: UnifiedAutomationQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val partyPresetQueryRepository: PartyPresetQueryRepository,
    private val timeProvider: TimeProvider,
    private val wakeupPort: AutomationWakeupPort,
) {
    /**
     * 통합 프로필과 현재 실행 상태를 조회한다.
     *
     * 최초 조회라 프로필이 없으면 빈 프로필 부모만 생성한다. 고정 모듈이나 기본 설정을 합성하지 않기
     * 때문에 신규 사용자는 항상 `modules=[]`에서 직접 구성을 시작한다.
     */
    @Transactional
    fun get(accountId: Long): UnifiedAutomationStatusResponse {
        val profile = findOrCreateProfile(accountId)
        return buildStatus(profile, queryRepository.findCurrentJob(accountId), queryRepository.findModules(profile.id))
    }

    /**
     * 새 모듈을 현재 목록 끝에 추가한다.
     *
     * 같은 유형도 독립 인스턴스로 반복 생성할 수 있으며, 맵과 프리셋을 일괄 조회해 모든 참조가 실제로
     * 존재하고 요청 계정 소유인지 확인한 뒤 정규화 자식 행을 저장한다.
     */
    @Transactional
    fun createModule(
        accountId: Long,
        request: CreateAutomationModuleRequest,
    ): AutomationModuleResponse {
        val settings = normalizeAndValidate(
            moduleType = request.moduleType,
            displayName = request.displayName,
            enabled = request.enabled,
            thresholdPercent = request.thresholdPercent,
            maps = request.maps,
            quests = request.quests,
        )
        val profile = lockOrCreateProfile(accountId)
        val existingModules = queryRepository.findModules(profile.id)
        if (existingModules.size >= MAX_MODULES) {
            invalid("자동화 모듈은 최대 100개까지 만들 수 있습니다.")
        }
        val references = resolveReferences(accountId, settings)
        val now = timeProvider.now()
        val config = moduleConfigRepository.save(
            AutomationModuleConfigEntity(
                profile = profile,
                moduleType = request.moduleType,
                enabled = settings.enabled,
                priority = existingModules.size,
                displayName = settings.displayName,
                thresholdPercent = settings.thresholdPercent,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val children = saveChildren(config, settings, references)
        touchProfile(profile, now)
        reconcileJobAfterModulesChanged(accountId, profile)
        return AutomationModuleAggregate(config, children.maps, children.quests).toResponse()
    }

    /**
     * 모듈 유형과 우선순위를 유지한 채 편집 가능한 설정을 완전히 교체한다.
     *
     * 기존 자식 행은 FK 의존 순서대로 삭제·flush한 후 새 요청으로 재생성한다. 따라서 같은 맵을 계속
     * 사용해도 유일 제약 충돌이 발생하지 않고, 요청에서 제거한 설정이 DB에 남지 않는다.
     */
    @Transactional
    fun updateModule(
        accountId: Long,
        moduleId: Long,
        request: UpdateAutomationModuleRequest,
    ): AutomationModuleResponse {
        lockOrCreateProfile(accountId)
        val existing = findOwnedModule(accountId, moduleId)
        val settings = normalizeAndValidate(
            moduleType = existing.config.moduleType,
            displayName = request.displayName,
            enabled = request.enabled,
            thresholdPercent = request.thresholdPercent,
            maps = request.maps,
            quests = request.quests,
        )
        val references = resolveReferences(accountId, settings)
        deleteChildren(existing)
        val now = timeProvider.now()
        existing.config.displayName = settings.displayName
        existing.config.enabled = settings.enabled
        existing.config.thresholdPercent = settings.thresholdPercent
        existing.config.updatedAt = now
        val config = moduleConfigRepository.save(existing.config)
        val children = saveChildren(config, settings, references)
        touchProfile(config.profile, now)
        reconcileJobAfterModulesChanged(accountId, config.profile)
        return AutomationModuleAggregate(config, children.maps, children.quests).toResponse()
    }

    /**
     * 계정이 소유한 모듈을 삭제하고 남은 모듈 순서를 0부터 연속된 값으로 보정한다.
     *
     * 현재 job과 action은 건드리지 않는다. 진행 중인 전투는 기존 실행 문맥으로 끝나고, 다음 의사결정은
     * 삭제 후 목록을 다시 읽도록 wakeup만 보낸다.
     */
    @Transactional
    fun deleteModule(
        accountId: Long,
        moduleId: Long,
    ) {
        lockOrCreateProfile(accountId)
        val target = findOwnedModule(accountId, moduleId)
        val remaining = queryRepository.findModules(target.config.profile.id)
            .filterNot { it.config.id == target.config.id }
        moduleConfigRepository.delete(target.config)
        moduleConfigRepository.flush()
        remaining.forEachIndexed { index, aggregate ->
            aggregate.config.priority = index
            aggregate.config.updatedAt = timeProvider.now()
        }
        if (remaining.isNotEmpty()) moduleConfigRepository.saveAll(remaining.map(AutomationModuleAggregate::config))
        touchProfile(target.config.profile, timeProvider.now())
        reconcileJobAfterModulesChanged(accountId, target.config.profile)
    }

    /**
     * 프로필 전체 모듈 ID를 앱이 전달한 순서로 저장한다.
     *
     * 누락·중복·다른 계정 ID를 부분 갱신으로 받아들이지 않고 현재 서버 집합과 정확히 일치할 때만 모든
     * 우선순위를 갱신한다. 저장 후 QueryDSL로 다시 읽은 목록을 반환해 앱 상태의 기준을 서버로 둔다.
     */
    @Transactional
    fun reorderModules(
        accountId: Long,
        request: ReorderAutomationModulesRequest,
    ): UnifiedAutomationStatusResponse {
        val profile = lockOrCreateProfile(accountId)
        val current = queryRepository.findModules(profile.id)
        validateCompleteOrder(current, request.moduleIds)
        val aggregateById = current.associateBy { it.config.id }
        val now = timeProvider.now()
        val ordered = request.moduleIds.mapIndexed { index, moduleId ->
            aggregateById.getValue(moduleId).also { aggregate ->
                aggregate.config.priority = index
                aggregate.config.updatedAt = now
            }
        }
        if (ordered.isNotEmpty()) {
            moduleConfigRepository.saveAll(ordered.map(AutomationModuleAggregate::config))
            moduleConfigRepository.flush()
        }
        touchProfile(profile, now)
        val mutationState = reconcileJobAfterModulesChanged(accountId, profile)
        return buildStatus(
            profile,
            mutationState.job,
            mutationState.modules,
        )
    }

    /** 준비 완료된 활성 모듈이 있을 때만 통합 자동화 job을 시작하거나 재개한다. */
    @Transactional
    fun start(accountId: Long): UnifiedAutomationStatusResponse {
        val profile = lockOrCreateProfile(accountId)
        val modules = queryRepository.findModules(profile.id)
        if (modules.none { it.config.enabled && it.isReadyForExecution() }) {
            invalid("실행할 수 있는 자동화가 없습니다. 사용할 모듈의 맵과 파티 설정을 확인해 주세요.")
        }
        val current = queryRepository.findCurrentJob(accountId)
        val job = current ?: newRunningJob(profile, timeProvider.now())
        if (job.status in setOf("PAUSED", "WAITING_CONFIG", "WAITING_LOGIN")) {
            val now = timeProvider.now()
            job.status = "RUNNING"
            job.finishedAt = null
            job.nextRunAt = now
            job.updatedAt = now
        }
        if (job.status == "RUNNING") wakeAfterCommit(accountId, "USER_START")
        return buildStatus(profile, job, modules)
    }

    /** 실행 중인 통합 자동화를 새 행동을 시작하지 않는 일시정지 상태로 전환한다. */
    @Transactional
    fun pause(accountId: Long): UnifiedAutomationStatusResponse =
        transition(accountId, setOf("PENDING", "RUNNING"), "PAUSED", finished = false)

    /** 사용자가 일시정지한 통합 자동화를 다음 의사결정부터 재개한다. */
    @Transactional
    fun resume(accountId: Long): UnifiedAutomationStatusResponse =
        transition(accountId, setOf("PAUSED"), "RUNNING", finished = false).also {
            wakeAfterCommit(accountId, "USER_RESUME")
        }

    /** 활성 상태의 통합 자동화를 종료하고 더 이상 다음 행동을 예약하지 않게 한다. */
    @Transactional
    fun stop(accountId: Long): UnifiedAutomationStatusResponse =
        transition(accountId, UnifiedAutomationQueryRepository.ACTIVE_STATUSES, "CANCELLED", finished = true)

    /** 상태 전이의 허용 집합과 종료 시각 처리를 한 곳에서 적용한다. */
    private fun transition(
        accountId: Long,
        allowed: Set<String>,
        target: String,
        finished: Boolean,
    ): UnifiedAutomationStatusResponse {
        val profile = queryRepository.findProfile(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "통합 자동화 설정을 찾지 못했습니다.")
        val job = queryRepository.findCurrentJob(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "실행 중인 통합 자동화가 없습니다.")
        if (job.status !in allowed) invalid("현재 상태에서는 요청한 동작을 수행할 수 없습니다.")
        val now = timeProvider.now()
        job.status = target
        job.message = when (target) {
            "PAUSED" -> "일시정지됨"
            "RUNNING" -> "재개 대기"
            else -> "종료됨"
        }
        job.updatedAt = now
        job.nextRunAt = if (target == "RUNNING") now else null
        if (finished) job.finishedAt = now
        return buildStatus(profile, job, queryRepository.findModules(profile.id))
    }

    /** 계정의 UNIFIED 프로필을 조회하고, 최초 접근일 때만 모듈 없는 부모 프로필을 만든다. */
    private fun findOrCreateProfile(accountId: Long): AutomationProfileEntity {
        queryRepository.findProfile(accountId)?.let { return it }
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val now = timeProvider.now()
        return profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = "통합 자동화",
                mode = UnifiedAutomationQueryRepository.UNIFIED_MODE,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * 설정 변경 전에 통합 프로필의 DB 쓰기 잠금을 획득한다.
     *
     * 신규 계정은 부모 프로필을 만든 뒤 flush하고 다시 잠금 조회한다. 일반 단위 테스트처럼 저장소가
     * 실제 트랜잭션을 제공하지 않는 문맥에서는 방금 만든 프로필을 그대로 사용하지만, 운영 JPA 경로는
     * 반드시 잠긴 managed entity를 반환한다.
     */
    private fun lockOrCreateProfile(accountId: Long): AutomationProfileEntity {
        queryRepository.findProfileForUpdate(accountId)?.let { return it }
        val created = findOrCreateProfile(accountId)
        profileRepository.flush()
        return queryRepository.findProfileForUpdate(accountId) ?: created
    }

    /** 같은 계정의 UNIFIED 프로필에 속한 모듈만 반환해 수정·삭제 경계를 보장한다. */
    private fun findOwnedModule(
        accountId: Long,
        moduleId: Long,
    ): AutomationModuleAggregate = queryRepository.findModule(accountId, moduleId)
        ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 모듈을 찾지 못했습니다.")

    /**
     * HTTP Bean Validation을 거치지 않는 내부 호출도 동일하게 보호하도록 문자열과 목록을 정규화한다.
     *
     * 유형별 필수·금지 설정과 목록 내 자연키·실행 순서 중복을 영속화 전에 거절해, 사용하지 않는 값이
     * 조용히 버려지거나 DB 제약 오류가 사용자에게 노출되지 않게 한다.
     */
    private fun normalizeAndValidate(
        moduleType: AutomationModuleType,
        displayName: String,
        enabled: Boolean,
        thresholdPercent: Int?,
        maps: List<AutomationModuleMapRequest>,
        quests: List<AutomationModuleQuestRequest>,
    ): NormalizedSettings {
        if (moduleType !in SUPPORTED_MODULE_TYPES) invalid("지원하지 않는 자동화 유형입니다.")
        val name = displayName.trim()
        if (name.isEmpty()) invalid("자동화 이름을 입력해 주세요.")
        if (name.length > MAX_DISPLAY_NAME_LENGTH) invalid("자동화 이름은 50자 이내로 입력해 주세요.")
        if (maps.size > MAX_SETTING_ITEMS || quests.size > MAX_SETTING_ITEMS) {
            invalid("자동화 설정은 항목별로 최대 100개까지 저장할 수 있습니다.")
        }
        val totalChildMaps = maps.size + quests.sumOf { it.maps.size }
        if (totalChildMaps > MAX_SETTING_ITEMS) {
            invalid("한 모듈의 전체 맵은 최대 100개까지 저장할 수 있습니다.")
        }
        validateTypeSpecificFields(moduleType, thresholdPercent, maps, quests)
        val normalizedMaps = normalizeMaps(maps, "모듈")
        val normalizedQuests = quests.map { quest ->
            val questCode = quest.questCode.trim()
            if (questCode.isEmpty()) invalid("퀘스트 코드를 입력해 주세요.")
            if (questCode.length > MAX_QUEST_CODE_LENGTH) invalid("퀘스트 코드는 100자 이내여야 합니다.")
            if (quest.executionOrder < 0) invalid("퀘스트 실행 순서는 0 이상이어야 합니다.")
            if (quest.maps.size > MAX_SETTING_ITEMS) invalid("퀘스트별 맵은 최대 100개까지 저장할 수 있습니다.")
            AutomationModuleQuestRequest(
                questCode = questCode,
                executionOrder = quest.executionOrder,
                maps = normalizeMaps(quest.maps, "퀘스트 $questCode"),
            )
        }
        rejectDuplicates(normalizedQuests.map { it.questCode }, "같은 퀘스트를 두 번 설정할 수 없습니다.")
        rejectDuplicates(normalizedQuests.map { it.executionOrder }, "퀘스트 실행 순서를 중복해서 사용할 수 없습니다.")

        val normalizedThreshold = if (moduleType == AutomationModuleType.TIME_BURN) {
            thresholdPercent?.takeIf { it in 1..100 }
                ?: invalid("Time 기준은 1%에서 100% 사이로 설정해 주세요.")
        } else {
            null
        }
        return NormalizedSettings(name, enabled, normalizedThreshold, normalizedMaps, normalizedQuests)
    }

    /**
     * 모듈 유형이 실제로 소비하는 필드만 받도록 요청 형태를 검증한다.
     *
     * 이 검증은 문자열·참조 정규화보다 먼저 실행한다. 사용하지 않는 필드에 잘못된 맵 코드 등이 함께
     * 들어와도 참조 오류 대신 제거해야 할 설정을 직접 안내하며, create와 update가 한 규칙을 공유한다.
     */
    private fun validateTypeSpecificFields(
        moduleType: AutomationModuleType,
        thresholdPercent: Int?,
        maps: List<AutomationModuleMapRequest>,
        quests: List<AutomationModuleQuestRequest>,
    ) {
        when (moduleType) {
            AutomationModuleType.TIME_BURN -> {
                if (quests.isNotEmpty()) {
                    invalid("Time 자동 소모에서는 퀘스트를 사용하지 않습니다. 퀘스트 설정을 제거해 주세요.")
                }
            }

            AutomationModuleType.KEY_QUEST -> {
                if (maps.isNotEmpty()) {
                    invalid("열쇠 퀘스트에서는 모듈 맵을 사용하지 않습니다. 맵 설정을 제거해 주세요.")
                }
                if (thresholdPercent != null) {
                    invalid("열쇠 퀘스트에서는 Time 기준을 사용하지 않습니다. Time 기준 설정을 제거해 주세요.")
                }
                if (quests.isEmpty()) invalid("실행할 퀘스트를 한 개 이상 선택해 주세요.")
            }

            AutomationModuleType.COOLDOWN_ADVENTURE -> {
                validateAdventureFields("쿨다운 모험맵", thresholdPercent, maps, quests)
            }

            AutomationModuleType.DAILY_ADVENTURE -> {
                validateAdventureFields("일일 제한 모험맵", thresholdPercent, maps, quests)
            }

            AutomationModuleType.OTHER_QUEST -> {
                if (maps.isNotEmpty()) {
                    invalid("일반 퀘스트에서는 모듈 맵을 사용하지 않습니다. 맵 설정을 제거해 주세요.")
                }
                if (quests.any { it.maps.isNotEmpty() }) {
                    invalid("일반 퀘스트에서는 퀘스트별 맵을 사용하지 않습니다. 퀘스트별 맵 설정을 제거해 주세요.")
                }
                if (thresholdPercent != null) {
                    invalid("일반 퀘스트에서는 Time 기준을 사용하지 않습니다. Time 기준 설정을 제거해 주세요.")
                }
                if (quests.isEmpty()) invalid("실행할 퀘스트를 한 개 이상 선택해 주세요.")
            }

            AutomationModuleType.UNION,
            AutomationModuleType.NORMAL_MAP,
            -> invalid("지원하지 않는 자동화 유형입니다.")
        }
    }

    /** 쿨다운·일일 모험이 공유하는 필수 맵과 금지 필드 규칙을 같은 사용자 문구 형식으로 검사한다. */
    private fun validateAdventureFields(
        typeLabel: String,
        thresholdPercent: Int?,
        maps: List<AutomationModuleMapRequest>,
        quests: List<AutomationModuleQuestRequest>,
    ) {
        if (quests.isNotEmpty()) {
            invalid("${typeLabel}에서는 퀘스트를 사용하지 않습니다. 퀘스트 설정을 제거해 주세요.")
        }
        if (thresholdPercent != null) {
            invalid("${typeLabel}에서는 Time 기준을 사용하지 않습니다. Time 기준 설정을 제거해 주세요.")
        }
        if (maps.isEmpty()) invalid("실행할 맵을 한 개 이상 선택해 주세요.")
    }

    /** 맵 문자열과 순서를 정규화하고 같은 목록 안의 맵·순서 중복을 제거한다. */
    private fun normalizeMaps(
        maps: List<AutomationModuleMapRequest>,
        ownerLabel: String,
    ): List<AutomationModuleMapRequest> {
        val normalized = maps.map { map ->
            val categoryId = map.categoryId.trim()
            val mapCode = map.mapCode.trim()
            if (categoryId.isEmpty() || mapCode.isEmpty()) invalid("$ownerLabel 맵 정보를 확인해 주세요.")
            if (categoryId.length > MAX_CATEGORY_ID_LENGTH || mapCode.length > MAX_MAP_CODE_LENGTH) {
                invalid("$ownerLabel 맵 식별자가 허용 길이를 초과했습니다.")
            }
            if (map.executionOrder < 0) invalid("$ownerLabel 맵 실행 순서는 0 이상이어야 합니다.")
            map.copy(categoryId = categoryId, mapCode = mapCode)
        }
        rejectDuplicates(normalized.map { it.categoryId to it.mapCode }, "같은 맵을 두 번 설정할 수 없습니다.")
        rejectDuplicates(normalized.map { it.executionOrder }, "$ownerLabel 맵 실행 순서를 중복해서 사용할 수 없습니다.")
        return normalized.sortedBy { it.executionOrder }
    }

    /** 요청의 모든 맵과 프리셋을 각각 한 번의 QueryDSL 조회로 검증한다. */
    private fun resolveReferences(
        accountId: Long,
        settings: NormalizedSettings,
    ): ResolvedReferences {
        val allMaps = settings.maps + settings.quests.flatMap { it.maps }
        val requestedPairs = allMaps.map { it.categoryId to it.mapCode }.toSet()
        val battleMaps = battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(requestedPairs)
        val battleMapByPair = battleMaps.associateBy { it.categoryId to it.mapCode }
        if (battleMapByPair.keys != requestedPairs) invalid("선택한 맵을 찾을 수 없습니다. 맵 목록을 새로고침해 주세요.")

        val requestedPresetIds = allMaps.mapNotNull { it.partyPresetId }.toSet()
        val presets = partyPresetQueryRepository.findOwnedByAccountIdAndIds(accountId, requestedPresetIds)
        val presetById = presets.associateBy(PartyPresetEntity::id)
        if (presetById.keys != requestedPresetIds) invalid("선택한 파티 프리셋을 찾을 수 없습니다.")
        return ResolvedReferences(battleMapByPair, presetById)
    }

    /** 정규화된 요청을 모듈 직속 맵, 퀘스트, 퀘스트별 맵 행으로 나누어 저장한다. */
    private fun saveChildren(
        config: AutomationModuleConfigEntity,
        settings: NormalizedSettings,
        references: ResolvedReferences,
    ): SavedChildren {
        val maps = settings.maps.map { request ->
            AutomationModuleMapEntity(
                moduleConfig = config,
                battleMap = references.battleMap(request),
                partyPreset = request.partyPresetId?.let(references.presets::getValue),
                executionOrder = request.executionOrder,
            )
        }
        if (maps.isNotEmpty()) moduleMapRepository.saveAll(maps)

        val quests = settings.quests.map { request ->
            val quest = moduleQuestRepository.save(
                AutomationModuleQuestEntity(
                    moduleConfig = config,
                    questCode = request.questCode,
                    executionOrder = request.executionOrder,
                ),
            )
            val questMaps = request.maps.map { mapRequest ->
                AutomationModuleQuestMapEntity(
                    moduleQuest = quest,
                    battleMap = references.battleMap(mapRequest),
                    partyPreset = mapRequest.partyPresetId?.let(references.presets::getValue),
                    executionOrder = mapRequest.executionOrder,
                )
            }
            if (questMaps.isNotEmpty()) moduleQuestMapRepository.saveAll(questMaps)
            AutomationModuleQuestAggregate(quest, questMaps)
        }
        return SavedChildren(maps, quests)
    }

    /** 수정 전 자식 행을 FK 의존 순서로 삭제하고 각 테이블의 삭제 SQL을 즉시 반영한다. */
    private fun deleteChildren(existing: AutomationModuleAggregate) {
        val questMaps = existing.quests.flatMap(AutomationModuleQuestAggregate::maps)
        if (questMaps.isNotEmpty()) {
            moduleQuestMapRepository.deleteAll(questMaps)
            moduleQuestMapRepository.flush()
        }
        if (existing.maps.isNotEmpty()) {
            moduleMapRepository.deleteAll(existing.maps)
            moduleMapRepository.flush()
        }
        val quests = existing.quests.map(AutomationModuleQuestAggregate::quest)
        if (quests.isNotEmpty()) {
            moduleQuestRepository.deleteAll(quests)
            moduleQuestRepository.flush()
        }
    }

    /** 순서 요청이 현재 모듈 전체를 정확히 한 번씩 포함하는지 검사한다. */
    private fun validateCompleteOrder(
        current: List<AutomationModuleAggregate>,
        requestedIds: List<Long>,
    ) {
        val currentIds = current.map { it.config.id }.toSet()
        if (requestedIds.size != requestedIds.toSet().size) invalid("자동화 순서에 같은 항목이 중복되었습니다.")
        if (requestedIds.size != current.size || requestedIds.toSet() != currentIds) {
            invalid("자동화 목록이 변경되었습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.")
        }
    }

    /**
     * 변경된 모듈 전체를 다시 읽어 설정 대기 job의 재개 여부와 응답 기준 상태를 한 번에 확정한다.
     *
     * 준비된 활성 모듈이 생긴 경우에만 `WAITING_CONFIG`를 `RUNNING`으로 전환한다. 이미 실행 중인
     * action과 module, step은 유지하며 다음 의사결정 시각만 현재로 당긴다. RUNNING job은 상태를
     * 수정하지 않고 커밋 뒤 wake만 예약한다.
     */
    private fun reconcileJobAfterModulesChanged(
        accountId: Long,
        profile: AutomationProfileEntity,
    ): ModuleMutationState {
        val modules = queryRepository.findModules(profile.id)
        val job = queryRepository.findCurrentJob(accountId)
        if (job?.status == "WAITING_CONFIG") {
            if (modules.none { it.config.enabled && it.isReadyForExecution() }) {
                return ModuleMutationState(job, modules)
            }
            val now = timeProvider.now()
            job.status = "RUNNING"
            job.message = "설정이 완료되어 자동화를 재개합니다."
            job.nextRunAt = now
            job.updatedAt = now
            jobRepository.save(job)
        }
        if (job?.status == "RUNNING") wakeAfterCommit(accountId, "MODULES_UPDATED")
        return ModuleMutationState(job, modules)
    }

    /**
     * 설정과 job 변경이 실제 DB에 커밋된 뒤에만 실행기를 깨운다.
     *
     * 트랜잭션 동기화가 활성화된 운영 요청에서는 rollback된 설정을 실행기가 읽지 않도록 afterCommit에
     * 등록한다. 서비스 단위 테스트나 배치 도구처럼 동기화가 없는 호출은 wake를 즉시 수행한다.
     */
    private fun wakeAfterCommit(
        accountId: Long,
        reason: String,
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            wakeupPort.wake(accountId, reason)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    wakeupPort.wake(accountId, reason)
                }
            },
        )
    }

    /** 프로필 수정 시각을 모듈 구성 변경 시각과 맞춘다. */
    private fun touchProfile(
        profile: AutomationProfileEntity,
        now: Instant,
    ) {
        profile.updatedAt = now
        profileRepository.save(profile)
    }

    /** 새 통합 자동화 job을 즉시 실행 가능한 초기 상태로 저장한다. */
    private fun newRunningJob(
        profile: AutomationProfileEntity,
        now: Instant,
    ): AutomationJobEntity = jobRepository.save(
        AutomationJobEntity(
            account = profile.account,
            profile = profile,
            status = "RUNNING",
            currentStepIndex = 0,
            message = "실행 준비",
            createdAt = now,
            startedAt = now,
            updatedAt = now,
            finishedAt = null,
            nextRunAt = now,
            lastHeartbeatAt = now,
        ),
    )

    /** aggregate의 정규화 자식 설정을 API 응답으로 변환하고 준비 상태와 요약을 계산한다. */
    private fun AutomationModuleAggregate.toResponse(): AutomationModuleResponse {
        val mapResponses = maps.sortedWith(compareBy(AutomationModuleMapEntity::executionOrder, AutomationModuleMapEntity::id))
            .map { it.toResponse() }
        val questResponses = quests
            .sortedWith(compareBy({ it.quest.executionOrder }, { it.quest.id }))
            .map { aggregate ->
                AutomationModuleQuestResponse(
                    questCode = aggregate.quest.questCode,
                    executionOrder = aggregate.quest.executionOrder,
                    maps = aggregate.maps
                        .sortedWith(compareBy(AutomationModuleQuestMapEntity::executionOrder, AutomationModuleQuestMapEntity::id))
                        .map { it.toResponse() },
                )
            }
        val ready = isReadyForExecution()
        return AutomationModuleResponse(
            id = config.id,
            displayName = config.displayName,
            moduleType = config.moduleType,
            enabled = config.enabled,
            priority = config.priority,
            thresholdPercent = config.thresholdPercent,
            maps = mapResponses,
            quests = questResponses,
            ready = ready,
            summary = summary(ready),
        )
    }

    /** 모듈 목록에서 설정 상태를 빠르게 파악할 수 있는 사용자 문구를 만든다. */
    private fun AutomationModuleAggregate.summary(ready: Boolean): String {
        if (!config.enabled) return "사용 안 함"
        return when (config.moduleType) {
            AutomationModuleType.KEY_QUEST -> if (ready) {
                "퀘스트 ${quests.size}개 · 맵 ${quests.sumOf { it.maps.size }}개"
            } else {
                "퀘스트별 맵과 파티를 설정해 주세요."
            }

            AutomationModuleType.TIME_BURN -> if (ready) {
                "${config.thresholdPercent}% 이상 · 맵 ${maps.size}개"
            } else {
                "전투 맵과 파티를 설정해 주세요."
            }

            AutomationModuleType.COOLDOWN_ADVENTURE ->
                if (ready) "쿨다운 맵 ${maps.size}개" else "쿨다운 맵과 파티를 설정해 주세요."

            AutomationModuleType.DAILY_ADVENTURE ->
                if (ready) "일일 제한 맵 ${maps.size}개" else "일일 제한 맵과 파티를 설정해 주세요."

            AutomationModuleType.OTHER_QUEST ->
                if (ready) "퀘스트 ${quests.size}개" else "실행할 퀘스트를 선택해 주세요."

            AutomationModuleType.UNION,
            AutomationModuleType.NORMAL_MAP,
            -> "지원하지 않는 자동화 유형입니다."
        }
    }

    private fun AutomationModuleMapEntity.toResponse() = AutomationModuleMapResponse(
        categoryId = battleMap.categoryId,
        mapCode = battleMap.mapCode,
        partyPresetId = partyPreset?.id,
        executionOrder = executionOrder,
    )

    private fun AutomationModuleQuestMapEntity.toResponse() = AutomationModuleMapResponse(
        categoryId = battleMap.categoryId,
        mapCode = battleMap.mapCode,
        partyPresetId = partyPreset?.id,
        executionOrder = executionOrder,
    )

    /** job 상태와 우선순위로 정렬한 모듈 응답을 하나의 권위 있는 화면 상태로 조립한다. */
    private fun buildStatus(
        profile: AutomationProfileEntity,
        job: AutomationJobEntity?,
        modules: List<AutomationModuleAggregate>,
    ) = UnifiedAutomationStatusResponse(
        profileId = profile.id,
        job = job?.toResponse(),
        modules = modules
            .sortedWith(compareBy({ it.config.priority }, { it.config.id }))
            .map { it.toResponse() },
        currentTitle = job?.currentAction ?: job?.message,
        nextRunAt = job?.nextRunAt?.toString(),
    )

    private fun AutomationJobEntity.toResponse() = AutomationJobResponse(
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

    private fun ResolvedReferences.battleMap(request: AutomationModuleMapRequest): BattleMapEntity =
        battleMaps.getValue(request.categoryId to request.mapCode)

    private fun <T> rejectDuplicates(
        values: List<T>,
        message: String,
    ) {
        if (values.size != values.toSet().size) invalid(message)
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private data class NormalizedSettings(
        val displayName: String,
        val enabled: Boolean,
        val thresholdPercent: Int?,
        val maps: List<AutomationModuleMapRequest>,
        val quests: List<AutomationModuleQuestRequest>,
    )

    private data class ResolvedReferences(
        val battleMaps: Map<Pair<String, String>, BattleMapEntity>,
        val presets: Map<Long, PartyPresetEntity>,
    )

    private data class SavedChildren(
        val maps: List<AutomationModuleMapEntity>,
        val quests: List<AutomationModuleQuestAggregate>,
    )

    private data class ModuleMutationState(
        val job: AutomationJobEntity?,
        val modules: List<AutomationModuleAggregate>,
    )

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 50
        const val MAX_CATEGORY_ID_LENGTH = 50
        const val MAX_MAP_CODE_LENGTH = 100
        const val MAX_QUEST_CODE_LENGTH = 100
        const val MAX_SETTING_ITEMS = 100
        const val MAX_MODULES = 100
        val SUPPORTED_MODULE_TYPES = setOf(
            AutomationModuleType.KEY_QUEST,
            AutomationModuleType.TIME_BURN,
            AutomationModuleType.COOLDOWN_ADVENTURE,
            AutomationModuleType.DAILY_ADVENTURE,
            AutomationModuleType.OTHER_QUEST,
        )
    }
}
