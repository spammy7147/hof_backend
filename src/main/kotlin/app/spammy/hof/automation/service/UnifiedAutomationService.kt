package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.KeyQuestSettingsRequest
import app.spammy.hof.automation.dto.NormalQuestSettingsRequest
import app.spammy.hof.automation.dto.TimeSettingsRequest
import app.spammy.hof.automation.dto.ToggleModuleRequest
import app.spammy.hof.automation.dto.UnifiedAutomationSettingsRequest
import app.spammy.hof.automation.dto.UnifiedAutomationStatusResponse
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationModuleConfigRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class UnifiedAutomationService(
    private val accountQueryRepository: AccountQueryRepository,
    private val profileRepository: AutomationProfileRepository,
    private val moduleConfigRepository: AutomationModuleConfigRepository,
    private val jobRepository: AutomationJobRepository,
    private val queryRepository: UnifiedAutomationQueryRepository,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun get(accountId: Long): UnifiedAutomationStatusResponse {
        val profile = findOrCreateProfile(accountId)
        return buildStatus(profile, queryRepository.findCurrentJob(accountId), readSettings(profile.id))
    }

    @Transactional
    fun update(
        accountId: Long,
        request: UnifiedAutomationSettingsRequest,
    ): UnifiedAutomationStatusResponse {
        validateSettings(request)
        val profile = findOrCreateProfile(accountId)
        val existing = queryRepository.findConfigs(profile.id)
        if (existing.isNotEmpty()) {
            moduleConfigRepository.deleteAll(existing)
            moduleConfigRepository.flush()
        }
        val now = timeProvider.now()
        moduleConfigRepository.saveAll(request.toConfigs(profile, now))
        profile.updatedAt = now
        return buildStatus(profile, queryRepository.findCurrentJob(accountId), request)
    }

    @Transactional
    fun start(accountId: Long): UnifiedAutomationStatusResponse = synchronized(ACCOUNT_JOB_LOCK) {
        val profile = findOrCreateProfile(accountId)
        val current = queryRepository.findCurrentJob(accountId)
        val job = current ?: newRunningJob(profile, timeProvider.now())
        if (job.status == "PAUSED") {
            job.status = "RUNNING"
            job.finishedAt = null
            job.updatedAt = timeProvider.now()
        }
        buildStatus(profile, job, readSettings(profile.id))
    }

    @Transactional
    fun pause(accountId: Long): UnifiedAutomationStatusResponse =
        transition(accountId, setOf("PENDING", "RUNNING"), "PAUSED", finished = false)

    @Transactional
    fun resume(accountId: Long): UnifiedAutomationStatusResponse =
        transition(accountId, setOf("PAUSED"), "RUNNING", finished = false)

    @Transactional
    fun stop(accountId: Long): UnifiedAutomationStatusResponse =
        transition(accountId, UnifiedAutomationQueryRepository.ACTIVE_STATUSES, "CANCELLED", finished = true)

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
        if (job.status !in allowed) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 상태에서는 요청한 동작을 수행할 수 없습니다.")
        }
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
        return buildStatus(profile, job, readSettings(profile.id))
    }

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

    private fun readSettings(profileId: Long): UnifiedAutomationSettingsRequest {
        val configs = queryRepository.findConfigs(profileId).associateBy { it.moduleType }
        return UnifiedAutomationSettingsRequest(
            keyQuest = configs[AutomationModuleType.KEY_QUEST]?.read(KeyQuestSettingsRequest::class.java)
                ?: KeyQuestSettingsRequest(),
            time = configs[AutomationModuleType.TIME_BURN]?.read(TimeSettingsRequest::class.java)
                ?: TimeSettingsRequest(),
            cooldownAdventure = configs[AutomationModuleType.COOLDOWN_ADVENTURE]
                ?.read(ToggleModuleRequest::class.java) ?: ToggleModuleRequest(),
            dailyAdventure = configs[AutomationModuleType.DAILY_ADVENTURE]
                ?.read(ToggleModuleRequest::class.java) ?: ToggleModuleRequest(),
            union = configs[AutomationModuleType.UNION]?.read(ToggleModuleRequest::class.java)
                ?: ToggleModuleRequest(),
            normalQuest = configs[AutomationModuleType.OTHER_QUEST]?.read(NormalQuestSettingsRequest::class.java)
                ?: NormalQuestSettingsRequest(),
        )
    }

    private fun UnifiedAutomationSettingsRequest.toConfigs(
        profile: AutomationProfileEntity,
        now: Instant,
    ): List<AutomationModuleConfigEntity> = listOf(
        config(profile, AutomationModuleType.KEY_QUEST, keyQuest.enabled, 0, keyQuest, now),
        config(profile, AutomationModuleType.TIME_BURN, time.enabled, 1, time, now),
        config(profile, AutomationModuleType.UNION, union.enabled, 2, union, now),
        config(profile, AutomationModuleType.COOLDOWN_ADVENTURE, cooldownAdventure.enabled, 3, cooldownAdventure, now),
        config(profile, AutomationModuleType.DAILY_ADVENTURE, dailyAdventure.enabled, 4, dailyAdventure, now),
        config(profile, AutomationModuleType.OTHER_QUEST, normalQuest.enabled, 5, normalQuest, now),
    )

    private fun config(
        profile: AutomationProfileEntity,
        type: AutomationModuleType,
        enabled: Boolean,
        priority: Int,
        settings: Any,
        now: Instant,
    ) = AutomationModuleConfigEntity(
        profile = profile,
        moduleType = type,
        enabled = enabled,
        priority = priority,
        settingsJson = objectMapper.writeValueAsString(settings),
        createdAt = now,
        updatedAt = now,
    )

    private fun <T> AutomationModuleConfigEntity.read(type: Class<T>): T =
        objectMapper.readValue(settingsJson, type)

    private fun validateSettings(request: UnifiedAutomationSettingsRequest) {
        val questIds = request.keyQuest.quests.map { it.questId.trim() }
        if (questIds.size != questIds.toSet().size) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "같은 퀘스트를 두 번 설정할 수 없습니다.")
        }
        request.keyQuest.quests.forEach { quest ->
            val maps = quest.maps.map { it.categoryId.trim() to it.mapCode.trim() }
            if (maps.size != maps.toSet().size) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "같은 맵을 두 번 선택할 수 없습니다.")
            }
        }
    }

    private fun buildStatus(
        profile: AutomationProfileEntity,
        job: AutomationJobEntity?,
        settings: UnifiedAutomationSettingsRequest,
    ) = UnifiedAutomationStatusResponse(
        profileId = profile.id,
        job = job?.toResponse(),
        settings = settings,
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

    private companion object {
        val ACCOUNT_JOB_LOCK = Any()
    }
}
