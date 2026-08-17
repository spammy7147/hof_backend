package app.spammy.hof.character.service

import app.spammy.hof.character.command.CharacterAutomationGate
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.external.parser.CharacterPageSection
import app.spammy.hof.external.parser.CharacterSectionParseResult
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFieldValue
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import org.springframework.stereotype.Service

data class CharacterDeepSyncResponse(
    val characterId: Long,
    val progress: List<CharacterDeepSyncProgress>,
)

/** 저장 슬롯을 차례로 관측하고 외부 현재 설정을 finally에서 복구하는 실제 깊은 동기화 진입점. */
@Service
class CharacterDeepSyncService(
    private val query: CharacterQueryRepository,
    private val executor: TownAuthenticatedExecutor,
    private val requestFactory: HofRequestFactory,
    private val parser: CharacterDetailParser,
    private val management: CharacterManagementService,
    private val archive: CharacterSnapshotArchiveWriter,
    private val timeProvider: TimeProvider,
    private val automationGate: CharacterAutomationGate,
    private val lifecycle: CharacterLifecycleService,
) {
    /** 보관 해제 시 같은 HOF ID의 실제 프로필을 확인한 경우에만 복원하고 전체 저장 설정을 다시 캡처한다. */
    fun restoreAndSynchronize(
        accountId: Long,
        characterId: Long,
        onProgress: (CharacterDeepSyncProgress) -> Unit = {},
    ): CharacterDeepSyncResponse? {
        val character = query.findByAccountIdAndId(accountId, characterId)
            ?: error("캐릭터를 찾지 못했습니다.")
        val parsed = executor.loadProjected(accountId, requestFactory.characterPage(character.hofCharacterId).url) { html, _, _ ->
            parser.parsePage(character.hofCharacterId, html)
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
        return synchronize(accountId, characterId, onProgress)
    }

    fun synchronize(
        accountId: Long,
        characterId: Long,
        onProgress: (CharacterDeepSyncProgress) -> Unit = {},
    ): CharacterDeepSyncResponse = automationGate.execute(
        accountId,
        unavailable = { error("자동화 일시정지를 기다리고 있습니다.") },
    ) {
        executor.executeAccountSequence(accountId) {
            val character = query.findByAccountIdAndId(accountId, characterId)
                ?: error("캐릭터를 찾지 못했습니다.")
            val progress = mutableListOf<CharacterDeepSyncProgress>()
            val remote = Remote(accountId, character.hofCharacterId)
            val store = object : CharacterDeepSyncStore {
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

    private inner class Remote(
        private val accountId: Long,
        private val hofCharacterId: String,
    ) : CharacterDeepSyncRemote {
        override fun captureCurrent(): CharacterPageParseResult = observe()

        override fun loadSavedPattern(slotCode: String): CharacterPageParseResult {
            val form = page().forms.singleOrNull { form ->
                form.submitSource.equals("loadpattern", true) &&
                    form.hiddenFields.singleOrNull { it.name.equals("patternno", true) }?.value == slotCode
            } ?: error("저장 패턴 슬롯을 찾지 못했습니다: $slotCode")
            execute(form)
            return observe()
        }

        override fun loadEquipmentPreset(slotNumber: Int): CharacterPageParseResult {
            val form = page().forms.singleOrNull { it.submitSource.equals("Equip_L_$slotNumber", true) }
                ?: error("장비 저장 슬롯 $slotNumber 불러오기를 찾지 못했습니다.")
            execute(form)
            return observe()
        }

        override fun restoreCurrent(original: CharacterPageParseResult): CharacterPageParseResult {
            restorePattern(original)
            restorePositionGuard(original)
            restoreEquipment(original)
            return observe()
        }

        private fun restorePattern(original: CharacterPageParseResult) {
            val rows = original.snapshot.actionPatterns
            val form = page().forms.singleOrNull { it.submitSource.equals("ChangePattern", true) }
                ?: error("Action Pattern 저장 form을 찾지 못했습니다.")
            val selections = rows.flatMap { row ->
                listOf(form.candidate("judge${row.index}", row.judge), form.candidate("skill${row.index}", row.skill))
            }.map { TownActionSelection(it.id) }
            val values = rows.map { row ->
                val field = form.editableFields.singleOrNull { it.inputName.equals("quantity${row.index}", true) }
                    ?: error("${row.index + 1}번 패턴 기준값 form을 찾지 못했습니다.")
                TownFieldValue(field.id, row.quantity)
            }
            execute(form, selections, values)
        }

        private fun restorePositionGuard(original: CharacterPageParseResult) {
            val form = page().forms.singleOrNull { candidateForm ->
                val names = candidateForm.candidates.map { it.inputName.lowercase() }.toSet()
                "position" in names && "guard" in names
            } ?: error("위치·호위 form을 찾지 못했습니다.")
            execute(
                form,
                listOf(
                    TownActionSelection(form.candidate("position", original.snapshot.positionGuard.selectedPosition).id),
                    TownActionSelection(form.candidate("guard", original.snapshot.positionGuard.guardValue).id),
                ),
            )
        }

        private fun restoreEquipment(original: CharacterPageParseResult) {
            page().forms.singleOrNull { it.submitSource.equals("remove_all", true) }?.let { execute(it) }
            original.snapshot.equipment.filter { it.name.isNotBlank() }.forEach { item ->
                // 장착 중인 아이템은 원래 Stock 후보에서 빠질 수 있으므로 전체 해제 뒤의 최신 후보를 사용한다.
                val current = observe().snapshot
                val matching = current.equipmentCandidates.filter {
                    it.name == item.name &&
                        it.typeCode.equals(item.slot, true)
                }
                val candidate = matching.singleOrNull()
                    ?: matching.singleOrNull {
                        item.description.isNotBlank() && it.description.contains(item.description)
                    }
                    ?: error("원래 장비를 고유하게 다시 찾지 못했습니다: ${item.part} / ${item.name}")
                val form = page().forms.singleOrNull { it.submitSource.equals("equip_item", true) }
                    ?: error("장비 장착 form을 찾지 못했습니다.")
                execute(form, listOf(TownActionSelection(form.candidate("item_no", candidate.value).id)))
            }
        }

        private fun observe(): CharacterPageParseResult = executor.loadProjected(accountId, characterUrl()) { html, _, _ ->
            parser.parsePage(hofCharacterId, html)
        }

        private fun page(): ParsedTownPage = executor.load(accountId, characterUrl())
        private fun characterUrl(): String = requestFactory.characterPage(hofCharacterId).url

        private fun execute(
            form: ParsedTownForm,
            selections: List<TownActionSelection> = emptyList(),
            values: List<TownFieldValue> = emptyList(),
        ) {
            management.execute(accountId, hofCharacterId, TownActionRequest(form.actionId, selections, values))
        }

        private fun ParsedTownForm.candidate(name: String, value: String) =
            candidates.singleOrNull { it.inputName.equals(name, true) && it.inputValue == value }
                ?: error("현재 선택할 수 없는 값입니다: $name=$value")
    }
}
