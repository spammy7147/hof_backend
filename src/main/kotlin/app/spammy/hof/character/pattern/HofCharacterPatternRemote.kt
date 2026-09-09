package app.spammy.hof.character.pattern

import app.spammy.hof.character.command.CharacterInternalFormExecutor
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFieldValue
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import org.springframework.stereotype.Component

interface CharacterPatternRemoteFactory {
    fun <T> withRemote(accountId: Long, characterId: Long, operation: (CharacterPatternRemote) -> T): T
}

/** 최신 form을 매 단계 재관측하면서도 전체 패턴 명령 동안 account fence를 유지한다. */
@Component
class HofCharacterPatternRemoteFactory(
    private val characters: CharacterQueryRepository,
    private val executor: TownAuthenticatedExecutor,
    private val internalForms: CharacterInternalFormExecutor,
    private val requestFactory: HofRequestFactory,
    private val detailParser: CharacterDetailParser,
    private val snapshots: CharacterSnapshotSynchronizer,
) : CharacterPatternRemoteFactory {
    override fun <T> withRemote(
        accountId: Long,
        characterId: Long,
        operation: (CharacterPatternRemote) -> T,
    ): T = executor.executeAccountSequence(accountId) {
        val character = characters.findByAccountIdAndId(accountId, characterId)
            ?: error("캐릭터를 찾지 못했습니다.")
        operation(Remote(accountId, characterId, character.hofCharacterId))
    }

    private inner class Remote(
        private val accountId: Long,
        private val characterId: Long,
        private val hofCharacterId: String,
    ) : CharacterPatternRemote {
        override fun observe(): CharacterPatternRemoteState = executor.loadProjected(accountId, characterUrl()) { html, finalUrl, page ->
            val parsed = checkNotNull(detailParser.parseCompletePatternPage(hofCharacterId, html, finalUrl)) {
                "요청한 캐릭터의 패턴·위치·호위를 완전히 확인하지 못했습니다."
            }
            val snapshot = parsed.snapshot
            snapshots.writeParsed(accountId, hofCharacterId, parsed)
            val character = characters.findByAccountIdAndId(accountId, characterId)
                ?: error("캐릭터를 찾지 못했습니다.")
            CharacterPatternRemoteState(
                revision = character.updatedAt,
                setting = CharacterPatternSetting(
                    rows = snapshot.actionPatterns.map { CharacterPatternRowValue(it.judge, it.quantity, it.skill) },
                    position = snapshot.positionGuard.selectedPosition,
                    guard = snapshot.positionGuard.guardValue,
                ),
                capacity = snapshot.actionPatterns.size,
                judgeValues = snapshot.patternOptions.filter { it.type.equals("CONDITION", true) }.mapTo(linkedSetOf()) { it.value },
                skillValues = snapshot.patternOptions.filter { it.type.equals("SKILL", true) }.mapTo(linkedSetOf()) { it.value },
                positionValues = snapshot.positionGuard.positions.mapTo(linkedSetOf()) { it.value },
                guardValues = page.forms.flatMap(ParsedTownForm::candidates)
                    .filter { it.inputName.equals("guard", true) }.mapTo(linkedSetOf()) { it.inputValue },
                savedSlots = snapshot.patternSlots.map { CharacterPatternRemoteSlot(it.slot, it.label, it.canLoad) },
            )
        }

        override fun changeAllRows(rows: List<CharacterPatternRowValue>): CharacterPatternMutationReceipt = mutate { page ->
            val form = page.forms.singleOrNull { it.submitSource.equals("ChangePattern", true) }
                ?: error("Action Pattern 저장 form을 찾지 못했습니다.")
            val selections = rows.flatMapIndexed { index, row ->
                listOf(
                    form.candidate("judge$index", row.judge),
                    form.candidate("skill$index", row.skill),
                )
            }.map { TownActionSelection(it.id) }
            val values = rows.mapIndexed { index, row ->
                val field = form.editableFields.singleOrNull { it.inputName.equals("quantity$index", true) }
                    ?: error("${index + 1}번 패턴 기준값 form을 찾지 못했습니다.")
                TownFieldValue(field.id, row.quantity)
            }
            TownActionRequest(form.actionId, selections, values)
        }

        override fun changePositionGuard(position: String, guard: String): CharacterPatternMutationReceipt = mutate { page ->
            val form = page.forms.singleOrNull { candidateForm ->
                val names = candidateForm.candidates.map { it.inputName.lowercase() }.toSet()
                "position" in names && "guard" in names
            } ?: error("위치·호위 form을 찾지 못했습니다.")
            TownActionRequest(
                form.actionId,
                listOf(
                    TownActionSelection(form.candidate("position", position).id),
                    TownActionSelection(form.candidate("guard", guard).id),
                ),
            )
        }

        override fun saveSlot(slotCode: String, name: String): CharacterPatternMutationReceipt = mutate { page ->
            val form = slotForm(page, "savepattern", slotCode)
            val field = form.editableFields.singleOrNull { it.inputName.equals("patternname", true) }
                ?: error("저장 패턴 이름 form을 찾지 못했습니다.")
            TownActionRequest(form.actionId, values = listOf(TownFieldValue(field.id, name)))
        }

        override fun deleteSlot(slotCode: String): CharacterPatternMutationReceipt = mutate { page ->
            val form = slotForm(page, "delpattern", slotCode)
            TownActionRequest(form.actionId)
        }

        override fun loadSlot(slotCode: String): CharacterPatternMutationReceipt = mutate { page ->
            val form = slotForm(page, "loadpattern", slotCode)
            TownActionRequest(form.actionId)
        }

        private fun slotForm(page: ParsedTownPage, source: String, slotCode: String): ParsedTownForm =
            page.forms.singleOrNull { form ->
                form.submitSource.equals(source, true) &&
                    form.hiddenFields.singleOrNull { it.name.equals("patternno", true) }?.value == slotCode
            } ?: error("저장 패턴 슬롯 form을 찾지 못했습니다: $slotCode")

        private fun ParsedTownForm.candidate(inputName: String, inputValue: String) =
            candidates.singleOrNull { it.inputName.equals(inputName, true) && it.inputValue == inputValue }
                ?: error("현재 선택할 수 없는 패턴 값입니다: $inputName=$inputValue")

        private fun characterUrl(): String = requestFactory.characterPage(hofCharacterId).url

        /** 패턴 내부 명령도 저장한 opaque ID를 재사용하지 않고 제출 직전 GET에서 다시 resolve한다. */
        private fun mutate(resolve: (ParsedTownPage) -> TownActionRequest): CharacterPatternMutationReceipt = try {
            internalForms.execute(accountId, hofCharacterId, resolve)
            CharacterPatternMutationReceipt.RESPONSE_RECEIVED
        } catch (_: RuntimeException) {
            // POST가 반영된 뒤 응답만 유실됐을 수 있으므로 자동 재전송하지 않고 orchestrator가 fresh GET으로 판정한다.
            CharacterPatternMutationReceipt.RESPONSE_LOST
        }

    }
}
