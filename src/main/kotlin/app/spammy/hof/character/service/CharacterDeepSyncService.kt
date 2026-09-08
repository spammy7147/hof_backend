package app.spammy.hof.character.service

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.command.CharacterAutomationGate
import app.spammy.hof.character.command.CharacterCommand
import app.spammy.hof.character.command.CharacterCommandContext
import app.spammy.hof.character.command.CharacterCommandObservation
import app.spammy.hof.character.command.CharacterCommandRemote
import app.spammy.hof.character.command.CharacterCommandRemoteSession
import app.spammy.hof.character.command.CharacterInternalFormExecutor
import app.spammy.hof.character.command.CharacterEquipmentCommandRules
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.external.parser.CharacterPageSection
import app.spammy.hof.external.parser.CharacterSectionParseResult
import app.spammy.hof.external.parser.HofHtmlParser
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFieldValue
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.net.URI
import org.springframework.stereotype.Service

data class CharacterDeepSyncResponse(
    val characterId: Long,
    val progress: List<CharacterDeepSyncProgress>,
)

/** 저장 슬롯을 관측하고 작업에 보존한 최초 원본으로 복구하는 깊은 동기화 진입점. */
@Service
class CharacterDeepSyncService(
    private val query: CharacterQueryRepository,
    private val executor: TownAuthenticatedExecutor,
    private val commandRemote: CharacterCommandRemote,
    private val internalForms: CharacterInternalFormExecutor,
    private val requestFactory: HofRequestFactory,
    private val parser: CharacterDetailParser,
    private val archive: CharacterSnapshotArchiveWriter,
    private val timeProvider: TimeProvider,
    private val automationGate: CharacterAutomationGate,
    private val lifecycle: CharacterLifecycleService,
    private val recovery: CharacterDeepSyncRecovery,
    private val sessionRecovery: HofSessionRecoveryService,
) {
    fun observeCurrent(accountId: Long, characterId: Long): CharacterPageParseResult {
        val character = query.findByAccountIdAndId(accountId, characterId) ?: error("캐릭터를 찾지 못했습니다.")
        return executor.loadProjected(accountId, requestFactory.characterPage(character.hofCharacterId).url) { html, finalUrl, _ ->
            parseObservation(character.hofCharacterId, html, finalUrl)
        }
    }

    fun recordCurrentObservation(accountId: Long, characterId: Long, page: CharacterPageParseResult) {
        val character = query.findByAccountIdAndId(accountId, characterId) ?: error("캐릭터를 찾지 못했습니다.")
        check(character.hofCharacterId == page.snapshot.id) { "관측한 HOF 캐릭터가 변경되었습니다." }
        archive.saveCurrent(character, page, timeProvider.now())
    }

    /** 보관 해제 시 같은 HOF ID의 실제 프로필을 확인한 경우에만 복원하고 전체 저장 설정을 다시 캡처한다. */
    fun restoreAndSynchronize(
        accountId: Long,
        characterId: Long,
        jobId: Long,
        onProgress: (CharacterDeepSyncProgress) -> Unit = {},
    ): CharacterDeepSyncResponse? {
        val character = query.findByAccountIdAndId(accountId, characterId)
            ?: error("캐릭터를 찾지 못했습니다.")
        val parsed = sessionRecovery.execute(accountId) {
            executor.loadProjected(accountId, requestFactory.characterPage(character.hofCharacterId).url) { html, finalUrl, _ ->
                parseObservation(character.hofCharacterId, html, finalUrl)
            }
        }
        if (parsed.sections[CharacterPageSection.PROFILE] !is CharacterSectionParseResult.Success) return null
        // 현재 HOF 프로필 확인 전에는 ARCHIVED 상태를 해제하지 않는다. 실패한 복원이
        // 보관함에서 캐릭터를 사라지게 만들면 사용자가 다시 시도하거나 삭제할 수 없다.
        lifecycle.link(
            accountId,
            characterId,
            character.hofCharacterId,
            CharacterHofIdLinkReason.REAPPEARED,
            userConfirmed = false,
        )
        return synchronize(accountId, characterId, jobId, onProgress)
    }

    fun synchronize(
        accountId: Long,
        characterId: Long,
        jobId: Long,
        onProgress: (CharacterDeepSyncProgress) -> Unit = {},
    ): CharacterDeepSyncResponse = automationGate.executeJob(
        accountId,
        jobId,
        unavailable = { error("자동화 일시정지를 기다리고 있습니다.") },
    ) {
        // 재인증과 그 후 한 번의 재실행까지 같은 일시정지/복귀 경계 안에서 처리한다.
        sessionRecovery.execute(accountId) {
            commandRemote.withSession(accountId) { commandSession ->
                val character = query.findByAccountIdAndId(accountId, characterId)
                    ?: error("캐릭터를 찾지 못했습니다.")
                val progress = mutableListOf<CharacterDeepSyncProgress>()
                val remote = Remote(accountId, character.id, character.hofCharacterId, commandSession)
                val store = object : CharacterDeepSyncStore {
                    override fun loadCheckpoint() = recovery.load(jobId, accountId, characterId)

                    override fun saveCheckpoint(checkpoint: CharacterDeepSyncCheckpoint) =
                        recovery.save(jobId, accountId, characterId, checkpoint)

                    override fun saveCurrent(snapshot: CharacterPageParseResult) =
                        archive.saveCurrent(character, snapshot, timeProvider.now())

                    override fun savePatternSlot(slotCode: String, snapshot: CharacterPageParseResult) =
                        archive.savePatternSlot(character, slotCode, snapshot)

                    override fun saveEquipmentPreset(slotNumber: Int, snapshot: CharacterPageParseResult) =
                        archive.saveEquipmentPreset(character, slotNumber, snapshot, timeProvider.now())
                }
                CharacterDeepSyncOrchestrator().synchronize(remote, store) { step ->
                    progress += step
                    onProgress(step)
                }
                CharacterDeepSyncResponse(characterId, progress)
            }
        }
    }

    private fun parseObservation(hofCharacterId: String, html: String, finalUrl: String): CharacterPageParseResult {
        val observedIds = URI(finalUrl).query.orEmpty().split('&').filter { it.substringBefore('=') == "char" }
            .map { it.substringAfter('=', "") }
        check(observedIds == listOf(hofCharacterId)) { "원본 서버 응답의 HOF 캐릭터가 다릅니다." }
        return parser.parsePage(hofCharacterId, html)
    }

    private inner class Remote(
        private val accountId: Long,
        private val characterId: Long,
        private val hofCharacterId: String,
        private val commandSession: CharacterCommandRemoteSession,
    ) : CharacterDeepSyncRemote {
        override fun captureCurrent(): CharacterPageParseResult = observe()

        override fun loadSavedPattern(slotCode: String, beforeChange: CharacterSyncBeforeChange): CharacterPageParseResult {
            return execute(CharacterSyncChange.LOAD_PATTERN, beforeChange) { page ->
                val form = page.forms.singleOrNull { form ->
                    form.submitSource.equals("loadpattern", true) &&
                        form.hiddenFields.singleOrNull { it.name.equals("patternno", true) }?.value == slotCode
                } ?: error("저장 패턴 슬롯을 찾지 못했습니다: $slotCode")
                TownActionRequest(form.actionId)
            }
        }

        override fun loadEquipmentPreset(slotNumber: Int, beforeChange: CharacterSyncBeforeChange): CharacterPageParseResult {
            return execute(CharacterSyncChange.LOAD_EQUIPMENT, beforeChange) { page ->
                val form = page.forms.singleOrNull { it.submitSource.equals("Equip_L_$slotNumber", true) }
                    ?: error("장비 저장 슬롯 $slotNumber 불러오기를 찾지 못했습니다.")
                TownActionRequest(form.actionId)
            }
        }

        override fun restoreCurrent(
            original: CharacterRestoreState,
            beforeChange: CharacterSyncBeforeChange,
            afterChange: (CharacterPageParseResult) -> Unit,
        ): CharacterPageParseResult {
            var observed = observe()
            var current = CharacterRestoreState.capture(observed)
            check(current.hofCharacterId == original.hofCharacterId) { "복원 대상 HOF 캐릭터가 변경되었습니다." }
            // 장비가 제공하는 스킬을 복구한 뒤 최신 폼에서 행동 패턴을 선택한다.
            if (current.equipment != original.equipment) {
                observed = restoreEquipment(original, observed, beforeChange, afterChange)
                current = CharacterRestoreState.capture(observed)
            }
            if (current.patterns != original.patterns) {
                observed = restorePattern(original, beforeChange).also(afterChange)
                current = CharacterRestoreState.capture(observed)
            }
            if (current.position != original.position || current.guard != original.guard) {
                observed = restorePositionGuard(original, beforeChange).also(afterChange)
            }
            return observe()
        }

        private fun restorePattern(original: CharacterRestoreState, beforeChange: CharacterSyncBeforeChange): CharacterPageParseResult {
            val rows = original.patterns
            return execute(CharacterSyncChange.RESTORE_PATTERN, beforeChange) { page ->
                val form = page.forms.singleOrNull { it.submitSource.equals("ChangePattern", true) }
                    ?: error("Action Pattern 저장 form을 찾지 못했습니다.")
                val selections = rows.flatMap { row ->
                    listOf(form.candidate("judge${row.index}", row.judge), form.candidate("skill${row.index}", row.skill))
                }.map { TownActionSelection(it.id) }
                val values = rows.map { row ->
                    val field = form.editableFields.singleOrNull { it.inputName.equals("quantity${row.index}", true) }
                        ?: error("${row.index + 1}번 패턴 기준값 form을 찾지 못했습니다.")
                    TownFieldValue(field.id, row.quantity)
                }
                TownActionRequest(form.actionId, selections, values)
            }
        }

        private fun restorePositionGuard(original: CharacterRestoreState, beforeChange: CharacterSyncBeforeChange): CharacterPageParseResult {
            return execute(CharacterSyncChange.RESTORE_POSITION, beforeChange) { page ->
                val form = page.forms.singleOrNull { candidateForm ->
                    val names = candidateForm.candidates.map { it.inputName.lowercase() }.toSet()
                    "position" in names && "guard" in names
                } ?: error("위치·호위 form을 찾지 못했습니다.")
                TownActionRequest(form.actionId, listOf(
                    TownActionSelection(form.candidate("position", original.position).id),
                    TownActionSelection(form.candidate("guard", original.guard).id),
                ))
            }
        }

        private fun restoreEquipment(
            original: CharacterRestoreState,
            initial: CharacterPageParseResult,
            beforeChange: CharacterSyncBeforeChange,
            afterChange: (CharacterPageParseResult) -> Unit,
        ): CharacterPageParseResult {
            val context = CharacterCommandContext(accountId, characterId, hofCharacterId)
            var observed = initial
            val acceptEquipmentChange: (String, String) -> Unit = { html, finalUrl ->
                observed = parseAcceptedResponse(html, finalUrl).also(afterChange)
            }
            val remaining = original.equipment.toMutableList()
            val containsOtherEquipment = CharacterRestoreState.capture(observed).equipment.map { remaining.remove(it) }.any { !it }
            if (containsOtherEquipment) {
                beforeChange(CharacterRestoreState.capture(observed), CharacterSyncChange.RESTORE_EQUIPMENT)
                when (val removed = commandSession.execute(
                    context,
                    CharacterCommand.RemoveAllEquipment(characterId, timeProvider.now()),
                    acceptEquipmentChange,
                )) {
                    is CharacterCommandObservation.Applied -> Unit
                    is CharacterCommandObservation.Rejected -> if (removed.code != "FORM_NOT_OBSERVED") {
                        error("원래 장비를 해제하지 못했습니다: ${removed.message}")
                    }
                    else -> error("원래 장비 해제 결과를 확인하지 못했습니다.")
                }
                remaining.clear()
                remaining += original.equipment
            }
            remaining.forEach { item ->
                // 복원 도중 이미 장착한 항목은 유지하고 빠진 원본만 최신 Stock 후보에서 고른다.
                val current = observed.snapshot
                val candidate = CharacterEquipmentCommandRules.requireRestoreCandidate(
                    item.name,
                    item.iconUrl,
                    item.description,
                    current.equipmentCandidates,
                )
                beforeChange(CharacterRestoreState.capture(observed), CharacterSyncChange.RESTORE_EQUIPMENT)
                val equipped = commandSession.execute(
                    context,
                    CharacterCommand.EquipItem(characterId, timeProvider.now(), candidate.value),
                    acceptEquipmentChange,
                )
                if (equipped !is CharacterCommandObservation.Applied) {
                    error("원래 장비를 다시 장착하지 못했습니다: ${item.part} / ${item.name}")
                }
            }
            return observed
        }

        private fun observe(): CharacterPageParseResult = executor.loadProjected(accountId, characterUrl()) { html, finalUrl, _ ->
            parseObservation(hofCharacterId, html, finalUrl)
        }

        private fun characterUrl(): String = requestFactory.characterPage(hofCharacterId).url

        /** 깊은 동기화 내부 form은 제출 직전 GET에서 다시 resolve해 stale action ID를 거부한다. */
        private fun execute(change: CharacterSyncChange, beforeChange: CharacterSyncBeforeChange, resolve: (ParsedTownPage) -> TownActionRequest): CharacterPageParseResult =
            internalForms.execute(accountId, hofCharacterId, beforeSubmit = { html, finalUrl ->
                beforeChange(CharacterRestoreState.capture(parseObservation(hofCharacterId, html, finalUrl)), change)
            }, afterSubmit = { html, finalUrl ->
                // 오류/불완전 응답 뒤 GET이 정상이어도 슬롯 불러오기 성공으로 바꾸지 않는다.
                parseAcceptedResponse(html, finalUrl)
            }, resolve = resolve)

        private fun parseAcceptedResponse(html: String, finalUrl: String): CharacterPageParseResult {
            check(HofHtmlParser.parse(html).select(".error").none { it.text().isNotBlank() }) {
                "원본 서버가 캐릭터 설정 변경을 거부했습니다."
            }
            return parseObservation(hofCharacterId, html, finalUrl).also { CharacterRestoreState.capture(it) }
        }

        private fun ParsedTownForm.candidate(name: String, value: String) =
            candidates.singleOrNull { it.inputName.equals(name, true) && it.inputValue == value }
                ?: error("현재 선택할 수 없는 값입니다: $name=$value")
    }

}
