package app.spammy.hof.character.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.character.dto.CharacterActionCandidateResponse
import app.spammy.hof.character.dto.CharacterActionFieldResponse
import app.spammy.hof.character.dto.CharacterManagementSnapshotResponse
import app.spammy.hof.character.dto.CharacterObservedActionResponse
import app.spammy.hof.character.dto.CharacterIdentityCandidateResponse
import app.spammy.hof.character.identity.CharacterIdentityEvidence
import app.spammy.hof.character.identity.CharacterIdentityResolution
import app.spammy.hof.character.identity.CharacterIdentityResolver
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
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
    private val rosterParser: CharacterRosterParser,
    private val characterService: CharacterService,
    private val identityResolver: CharacterIdentityResolver,
    private val lifecycleService: CharacterLifecycleService,
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
        val before = characterService.findAll(accountId)
        val observed = executor.load(accountId, characterUrl(hofCharacterId)).forms
            .singleOrNull { it.actionId == action.actionId }
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "현재 페이지에서 실행할 수 없는 작업입니다.")
        val executed = executor.execute(accountId, characterUrl(hofCharacterId), action)
        if (observed.submitSource.isTerminalIdentityAction()) {
            return reconcileRosterAfterIdentityChange(
                accountId = accountId,
                previousHofCharacterId = hofCharacterId,
                previousCharacters = before,
                actionSource = observed.submitSource.lowercase(),
                messages = executed.result.messages,
            )
        }
        return loadFresh(accountId, hofCharacterId, executed.result.messages)
    }

    private fun reconcileRosterAfterIdentityChange(
        accountId: Long,
        previousHofCharacterId: String,
        previousCharacters: List<app.spammy.hof.character.dto.CharacterResponse>,
        actionSource: String,
        messages: List<String>,
    ): CharacterManagementSnapshotResponse {
        val roster = executor.loadProjected(accountId, requestFactory.home().url) { html, _, _ ->
            rosterParser.parse(html)
        }
        if (roster.isEmpty()) {
            throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "캐릭터 작업 후 HOF 캐릭터 목록을 확인하지 못했습니다.")
        }
        characterService.deleteCharactersAbsentFromRoster(accountId, roster.mapTo(linkedSetOf()) { it.id })

        if (actionSource.startsWith("byebye")) {
            previousCharacters.singleOrNull { it.hofCharacterId == previousHofCharacterId }
                ?.let { lifecycleService.archive(accountId, it.id) }
            return CharacterManagementSnapshotResponse(
                character = null,
                actions = emptyList(),
                messages = messages.ifEmpty { listOf("캐릭터를 삭제했습니다.") },
                characters = characterService.findAll(accountId),
                targetRemoved = true,
            )
        }

        val target = previousCharacters.singleOrNull { it.hofCharacterId == previousHofCharacterId }
            ?: throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "순서 변경 전 캐릭터를 확인하지 못했습니다.")
        val resolution = identityResolver.resolveKnockback(
            targetBefore = target.toIdentityEvidence(),
            rosterBefore = previousCharacters.map { it.toIdentityEvidence() },
            rosterAfter = roster.map { CharacterIdentityEvidence(it.id, it.name, it.job, it.level) },
        )
        val replacement = when (resolution) {
            is CharacterIdentityResolution.Confirmed -> resolution.replacement
            is CharacterIdentityResolution.Candidates -> return identityResolutionResponse(
                accountId,
                messages,
                roster.map { rosterCharacter ->
                    val candidate = resolution.candidates.singleOrNull {
                        it.character.hofCharacterId == rosterCharacter.id
                    }
                    CharacterIdentityCandidateResponse(
                        rosterCharacter.id,
                        rosterCharacter.name,
                        rosterCharacter.job,
                        rosterCharacter.level,
                        candidate?.matchingFields.orEmpty(),
                    )
                },
            )
            is CharacterIdentityResolution.Unresolved -> return identityResolutionResponse(
                accountId,
                messages,
                resolution.roster.map { candidate ->
                    CharacterIdentityCandidateResponse(candidate.hofCharacterId, candidate.name, candidate.job, candidate.level)
                },
            )
        }
        lifecycleService.link(
            accountId,
            target.id,
            replacement.hofCharacterId,
            CharacterHofIdLinkReason.KNOCKBACK,
            userConfirmed = false,
        )
        val snapshot = loadFresh(
            accountId = accountId,
            hofCharacterId = replacement.hofCharacterId,
            messages = messages.ifEmpty { listOf("캐릭터를 맨 뒤로 이동했습니다.") },
        )
        return snapshot.copy(characters = characterService.findAll(accountId))
    }

    private fun identityResolutionResponse(
        accountId: Long,
        messages: List<String>,
        candidates: List<CharacterIdentityCandidateResponse>,
    ) = CharacterManagementSnapshotResponse(
        character = null,
        actions = emptyList(),
        messages = messages.ifEmpty { listOf("새 캐릭터 연결을 선택해 주세요.") },
        characters = characterService.findAll(accountId),
        identityResolutionRequired = true,
        identityCandidates = candidates,
    )

    private fun loadFresh(
        accountId: Long,
        hofCharacterId: String,
        messages: List<String> = emptyList(),
    ): CharacterManagementSnapshotResponse {
        accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return executor.loadProjected(accountId, characterUrl(hofCharacterId)) { html, _, page ->
            val parsed = detailParser.parsePage(hofCharacterId, html)
            val refreshed = snapshotSynchronizer.writeParsed(accountId, hofCharacterId, parsed)
            CharacterManagementSnapshotResponse(
                character = refreshed,
                actions = page.toActions(parsed.snapshot.patternSlots.associate { it.slot to it.label }),
                messages = messages,
                characters = characterService.findAll(accountId),
            )
        }
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

    private fun String.isTerminalIdentityAction(): Boolean =
        equals("byebye", ignoreCase = true) || equals("knockback", ignoreCase = true)
}

private fun app.spammy.hof.character.dto.CharacterResponse.toIdentityEvidence() =
    CharacterIdentityEvidence(hofCharacterId, name, job, level)
