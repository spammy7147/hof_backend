package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.dto.CharacterActionCandidateResponse
import app.spammy.hof.character.dto.CharacterActionFieldResponse
import app.spammy.hof.character.dto.CharacterManagementSnapshotResponse
import app.spammy.hof.character.dto.CharacterObservedActionResponse
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import org.springframework.stereotype.Service

/** 최신 HOF 캐릭터 페이지에 실제로 존재하는 관리 form만 앱에 노출하고 실행한다. */
@Service
class CharacterManagementService(
    private val accountQueryRepository: AccountQueryRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val executor: TownAuthenticatedExecutor,
    private val detailParser: CharacterDetailParser,
    private val snapshotSynchronizer: CharacterSnapshotSynchronizer,
    private val characterService: CharacterService,
) {
    fun load(accountId: Long, hofCharacterId: String): CharacterManagementSnapshotResponse {
        requireOwnedCharacter(accountId, hofCharacterId)
        return loadFresh(accountId, hofCharacterId)
    }

    fun execute(
        accountId: Long,
        hofCharacterId: String,
        action: TownActionRequest,
    ): CharacterManagementSnapshotResponse {
        requireOwnedCharacter(accountId, hofCharacterId)
        val observed = executor.load(accountId, characterUrl(hofCharacterId)).forms
            .singleOrNull { it.actionId == action.actionId }
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "현재 페이지에서 실행할 수 없는 작업입니다.")
        if (observed.submitSource.isIdentityAction()) {
            throw ApiException(
                ErrorCode.INVALID_REQUEST,
                "Kick과 Knockback은 캐릭터 의미 명령으로만 실행할 수 있습니다.",
            )
        }
        if (observed.submitSource.equals("showreset", ignoreCase = true)) {
            return executor.executeProjected(
                accountId = accountId,
                pageUrl = characterUrl(hofCharacterId),
                resolveAction = { _, _, _ -> action },
                projector = { html, _, result, page ->
                    writeFresh(accountId, hofCharacterId, html, page, result.messages)
                },
            )
        }
        val executed = executor.execute(accountId, characterUrl(hofCharacterId), action)
        return loadFresh(accountId, hofCharacterId, executed.result.messages)
    }

    fun executeResetItem(
        accountId: Long,
        hofCharacterId: String,
        itemValue: String,
    ): CharacterManagementSnapshotResponse? {
        requireOwnedCharacter(accountId, hofCharacterId)
        return executor.executeResolvedTwoStepProjected(
            accountId = accountId,
            pageUrl = characterUrl(hofCharacterId),
            requiredEntrySubmitField = "showreset",
            requiredFinalSubmitField = "resetVarious",
            entryAction = { page ->
                page.forms.singleOrNull { it.submitSource.equals("showreset", ignoreCase = true) }
                    ?.let { TownActionRequest(it.actionId) }
            },
            finalAction = { page ->
                page.forms.singleOrNull { it.submitSource.equals("resetVarious", ignoreCase = true) }
                    ?.let { form ->
                        form.candidates.singleOrNull {
                            it.inputName.equals("itemUse", ignoreCase = true) && it.inputValue == itemValue
                        }?.let { candidate ->
                            TownActionRequest(
                                form.actionId,
                                selections = listOf(TownActionSelection(candidate.id)),
                            )
                        }
                    }
            },
        ) { html, _, result, page ->
            writeFresh(accountId, hofCharacterId, html, page, result.messages)
        }
    }

    private fun loadFresh(
        accountId: Long,
        hofCharacterId: String,
        messages: List<String> = emptyList(),
    ): CharacterManagementSnapshotResponse {
        accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return executor.loadProjected(accountId, characterUrl(hofCharacterId)) { html, _, page ->
            writeFresh(accountId, hofCharacterId, html, page, messages)
        }
    }

    private fun writeFresh(
        accountId: Long,
        hofCharacterId: String,
        html: String,
        page: ParsedTownPage,
        messages: List<String>,
    ): CharacterManagementSnapshotResponse {
        val parsed = detailParser.parsePage(hofCharacterId, html)
        val refreshed = snapshotSynchronizer.writeParsed(accountId, hofCharacterId, parsed)
        return CharacterManagementSnapshotResponse(
            character = refreshed,
            actions = page.toActions(parsed.snapshot.patternSlots.associate { it.slot to it.label }),
            messages = messages,
            characters = characterService.findAll(accountId),
        )
    }

    private fun requireOwnedCharacter(accountId: Long, hofCharacterId: String) {
        characterQueryRepository.findByAccountIdAndHofCharacterId(accountId, hofCharacterId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
    }

    private fun ParsedTownPage.toActions(patternSlotLabels: Map<String, String>): List<CharacterObservedActionResponse> = forms
        .filter { it.submitLabel.isNotBlank() }
        .map { form ->
            val patternSlot = form.hiddenFields.singleOrNull { it.name.equals("patternno", true) }?.value
                ?.let(patternSlotLabels::get)
                ?.takeIf(String::isNotBlank)
            CharacterObservedActionResponse(
                actionId = form.actionId,
                source = form.submitSource,
                label = listOfNotNull(form.submitLabel, patternSlot).joinToString(" · "),
                candidates = form.candidates.map { candidate ->
                    CharacterActionCandidateResponse(
                        id = candidate.id,
                        groupId = candidate.inputName,
                        label = candidate.label,
                        selectionType = candidate.selectionType,
                        minQuantity = candidate.minQuantity,
                        maxQuantity = candidate.maxQuantity,
                        selected = candidate.selected,
                    )
                },
                fields = form.editableFields.map { field ->
                    CharacterActionFieldResponse(
                        id = field.id,
                        label = field.characterFieldLabel(),
                        value = field.value,
                        inputType = field.inputType,
                        maxLength = field.maxLength,
                    )
                },
            )
        }

    private fun app.spammy.hof.town.common.model.ParsedTownEditableField.characterFieldLabel(): String {
        Regex("^quantity(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(inputName)?.let { match ->
            return "${match.groupValues[1].toInt() + 1}번 기준값"
        }
        return when (inputName.lowercase()) {
            "newname" -> "새 캐릭터 이름"
            "patternname" -> "저장 패턴 이름"
            else -> label
        }
    }

    private fun characterUrl(hofCharacterId: String): String = requestFactory.characterPage(hofCharacterId).url

    private fun String.isIdentityAction(): Boolean = lowercase() in setOf(
        "knockback",
        "knockback2",
        "byebye",
        "byebye2",
        "byebye3",
    )
}
