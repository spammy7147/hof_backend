package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.*
import app.spammy.hof.automation.entity.AutomationPresetEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.repository.AutomationPresetCommandRepository
import app.spammy.hof.automation.repository.AutomationPresetQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class AutomationPresetService(
    private val accounts: AccountQueryRepository,
    private val queries: AutomationPresetQueryRepository,
    private val commands: AutomationPresetCommandRepository,
    private val typed: TypedAutomationQueryRepository,
    private val settings: UnifiedAutomationService,
    private val mapper: ObjectMapper,
    private val clock: TimeProvider,
    private val validator: jakarta.validation.Validator,
) {
    @Transactional(readOnly = true)
    fun list(accountId: Long): List<AutomationPresetResponse> {
        accounts.findById(accountId) ?: missing()
        return summaries(accountId)
    }

    @Transactional(readOnly = true)
    fun get(accountId: Long, presetId: Long): AutomationPresetDetailResponse {
        val preset = queries.findOwned(accountId, presetId) ?: missing()
        return detail(preset)
    }

    @Transactional(readOnly = true)
    fun getApplied(accountId: Long): AppliedAutomationPresetResponse? {
        val account = accounts.findById(accountId) ?: missing()
        val preset = account.appliedAutomationPresetId?.let { queries.findOwned(accountId, it) } ?: return null
        val saved = mapper.readValue(preset.settingsJson, AutomationPresetSettings::class.java)
        val current = AutomationPresetSettings.from(settings.getTyped(accountId).entries)
        return AppliedAutomationPresetResponse(preset.id, preset.name, preset.revision.toString(), comparable(saved) == comparable(current))
    }

    @Transactional
    fun save(accountId: Long, request: SaveAutomationPresetRequest, presetId: Long? = null): AutomationPresetDetailResponse {
        val account = accounts.findByIdForUpdate(accountId) ?: missing()
        val name = request.name.trim()
        if (name.isEmpty() || name.length > 100) invalid("프리셋 이름은 1~100자로 입력해 주세요.")
        val existing = presetId?.let { queries.findOwned(accountId, it) ?: missing() }
        existing?.let { requireRevision(it, request.revision) }
        val presets = queries.findAll(accountId)
        if (existing == null && presets.size >= 10) invalid("자동화 프리셋은 최대 10개까지 저장할 수 있습니다.")
        if (presets.any { it.id != presetId && it.name == name }) invalid("같은 이름의 자동화 프리셋이 있습니다.")
        val configuration = (request.settings ?: AutomationPresetSettings.from(settings.getTyped(accountId).entries)).forStorage()
        if (request.settings != null) {
            val violations = validator.validate(configuration) + configuration.entries.flatMap { validator.validate(it.toSettingsRequest()) }
            if (violations.isNotEmpty()) invalid(violations.first().message)
            settings.validatePresetSettings(accountId, configuration)
        }
        val json = mapper.writeValueAsString(configuration)
        val now = clock.now()
        val stored = if (existing == null) {
            commands.save(AutomationPresetEntity(account = account, name = name, settingsJson = json,
                entryCount = configuration.entries.size, createdAt = now, updatedAt = now))
        } else {
            existing.name = name
            existing.settingsJson = json
            existing.entryCount = configuration.entries.size
            existing.updatedAt = now
            existing
        }
        commands.flush()
        return detail(stored)
    }

    @Transactional
    fun delete(accountId: Long, presetId: Long): List<AutomationPresetResponse> {
        accounts.findByIdForUpdate(accountId) ?: missing()
        commands.delete(queries.findOwned(accountId, presetId) ?: missing())
        commands.flush()
        return summaries(accountId)
    }

    @Transactional
    fun apply(accountId: Long, presetId: Long, revision: String? = null): TypedAutomationAggregateResponse {
        return applyWithDetail(accountId, presetId, revision).automation
    }

    @Transactional
    fun applyWithDetail(accountId: Long, presetId: Long, revision: String? = null): AutomationPresetApplyResponse {
        val account = accounts.findByIdForUpdate(accountId) ?: missing()
        val preset = queries.findOwned(accountId, presetId) ?: missing()
        requireRevision(preset, revision)
        val runtime = typed.lockRuntimeState(accountId)
        if (runtime != null && (runtime.lifecycleStatus !in setOf(TypedAutomationLifecycle.PAUSED, TypedAutomationLifecycle.STOPPED)
                || runtime.resumeAfterAuth || runtime.leaseUntil?.isAfter(clock.now()) == true)
            || typed.findActiveTypedAction(accountId) != null || queries.hasExecutionLease(accountId, clock.now())) {
            invalid("자동화를 일시정지하고 진행 중인 행동이 끝난 뒤 프리셋을 불러와 주세요.")
        }
        val saved = mapper.readValue(preset.settingsJson, AutomationPresetSettings::class.java).entries
        val current = settings.getTyped(accountId).entries
        val retained = saved.map { entry ->
            current.firstOrNull { it.id == entry.sourceEntryId && it.type == entry.type }
                ?: current.firstOrNull { it.type == entry.type && it.type !in MAP_GROUP_TYPES }
        }
        val retainedIds = retained.mapNotNull { it?.id }.toSet()
        current.filter { it.id !in retainedIds }.forEach { settings.deleteEntry(accountId, it.id, it.settingsRevision) }
        // 묶음 간 맵 배치가 달라져도 중간 중복 없이 같은 트랜잭션에서 교체한다.
        retained.filterNotNull().forEach { entry ->
            when (entry.type) {
                AutomationType.BATTLE_MAP -> settings.updateBattleMapGroup(accountId, entry.id,
                    UpdateBattleMapGroupRequest(entry.settingsRevision, entry.displayName, false, emptyList()))
                AutomationType.ADVENTURE_MAP -> settings.updateAdventureMapGroup(accountId, entry.id,
                    UpdateAdventureMapGroupRequest(entry.settingsRevision, entry.displayName, false, emptyList()))
                else -> Unit
            }
        }
        val targetIds = saved.mapIndexed { index, entry ->
            val target = retained[index]?.let { old -> settings.getTyped(accountId).entries.single { it.id == old.id } }
                ?: settings.createEntry(accountId, CreateAutomationEntryRequest(entry.type)).entries.last()
            restoreEntry(accountId, target, entry)
            target.id
        }
        val response = settings.reorderEntries(accountId, ReorderAutomationEntriesRequest(targetIds))
        account.appliedAutomationPresetId = preset.id
        // 새 맵 묶음도 이후 적용에서 같은 항목으로 연결되도록 서버 정체성을 저장한다.
        preset.settingsJson = mapper.writeValueAsString(AutomationPresetSettings.from(response.entries))
        preset.updatedAt = clock.now()
        commands.flush()
        return AutomationPresetApplyResponse(response, detail(preset))
    }

    private fun restoreEntry(accountId: Long, target: TypedAutomationEntryResponse, saved: AutomationPresetEntry) {
        when (val request = saved.toSettingsRequest(target.settingsRevision)) {
            is UpdateColosseumAutomationRequest -> settings.updateColosseum(accountId, request)
            is UpdateQuestAutomationRequest -> settings.updateQuest(accountId, request)
            is UpdateHomeQuestAutomationRequest -> settings.updateHomeQuests(accountId, request)
            is UpdateBattleMapGroupRequest -> settings.updateBattleMapGroup(accountId, target.id, request)
            is UpdateAdventureMapGroupRequest -> settings.updateAdventureMapGroup(accountId, target.id, request)
            is UpdateFishingAutomationRequest -> settings.updateFishing(accountId, request)
            is UpdateUnionAutomationRequest -> settings.updateUnion(accountId, request)
            is UpdateRaidAutomationRequest -> settings.updateRaid(accountId, request)
        }
    }

    private fun requireRevision(preset: AutomationPresetEntity, revision: String?) {
        if (revision != preset.revision.toString()) throw ApiException(ErrorCode.AUTOMATION_SETTINGS_CONFLICT,
            "다른 곳에서 프리셋이 변경되었습니다. 편집 내용은 유지되므로 최신 저장본을 확인해 주세요.")
    }

    private fun comparable(configuration: AutomationPresetSettings) = configuration.forStorage().entries.map { it.copy(sourceEntryId = 0) }

    private fun summaries(accountId: Long) = queries.findAll(accountId).map {
        AutomationPresetResponse(it.id, it.name, it.entryCount, it.updatedAt.toString())
    }

    private fun detail(preset: AutomationPresetEntity) = AutomationPresetDetailResponse(preset.id, preset.name,
        preset.revision.toString(), mapper.readValue(preset.settingsJson, AutomationPresetSettings::class.java), preset.updatedAt.toString())

    private fun missing(): Nothing = throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 프리셋 또는 계정을 찾지 못했습니다.")
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private companion object {
        val MAP_GROUP_TYPES = setOf(AutomationType.BATTLE_MAP, AutomationType.ADVENTURE_MAP)
    }
}
