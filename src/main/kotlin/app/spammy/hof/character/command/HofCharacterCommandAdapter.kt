package app.spammy.hof.character.command

import app.spammy.hof.character.service.CharacterManagementService
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFieldValue
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownRequestContinuation
import org.springframework.stereotype.Component

/** 의미 명령을 최신 HOF form에서 다시 관측한 opaque action ID로 변환하는 유일한 adapter. */
@Component
class HofCharacterCommandAdapter(
    private val executor: TownAuthenticatedExecutor,
    private val management: CharacterManagementService,
    private val requestFactory: HofRequestFactory,
    private val rosterParser: CharacterRosterParser,
    private val detailParser: CharacterDetailParser,
    private val snapshots: CharacterSnapshotSynchronizer,
) : CharacterCommandRemote {
    override fun <T> withSession(accountId: Long, operation: (CharacterCommandRemoteSession) -> T): T =
        executor.executeAccountSequence(accountId) {
            var continuation: TownRequestContinuation? = null
            operation(
                object : CharacterCommandRemoteSession {
                    override fun observeRoster(): List<CharacterCommandObservedIdentity> =
                        this@HofCharacterCommandAdapter.observeRoster(accountId)

                    override fun execute(
                        context: CharacterCommandContext,
                        command: CharacterCommand,
                    ): CharacterCommandObservation = this@HofCharacterCommandAdapter.execute(context, command) {
                        continuation = it
                    }

                    override fun refreshSnapshot(hofCharacterId: String): Boolean = continuation?.let { active ->
                        this@HofCharacterCommandAdapter.refreshSnapshot(accountId, hofCharacterId, active)
                    } ?: false
                },
            )
        }

    private fun execute(
        context: CharacterCommandContext,
        command: CharacterCommand,
        rememberContinuation: (TownRequestContinuation) -> Unit,
    ): CharacterCommandObservation {
        val messages = when (command) {
            is CharacterCommand.Rename -> executeRename(context, command.newName)
            is CharacterCommand.Kick -> return executeIdentitySequence(
                context,
                listOf("byebye", "byebye2", "byebye3"),
                rememberContinuation,
            ) { roster, observedAt, messages -> CharacterCommandObservation.KickApplied(roster, observedAt, messages) }
            is CharacterCommand.Knockback -> return executeIdentitySequence(
                context,
                listOf("knockback", "knockback2"),
                rememberContinuation,
            ) { roster, observedAt, messages -> CharacterCommandObservation.KnockbackApplied(roster, observedAt, messages) }
            is CharacterCommand.Pray -> executeSimple(context, "pray")
            is CharacterCommand.PrepareItems -> executeSimple(context, "showreset")
            is CharacterCommand.UseItem ->
                executeChoice(context, "use_char_item", "item_no", command.itemValue)
                    ?: management.executeResetItem(
                        context.accountId,
                        context.hofCharacterId,
                        command.itemValue,
                    )?.messages
            is CharacterCommand.LearnSkill -> executeChoice(context, "learnskill", "newskill", command.skillValue)
            is CharacterCommand.ChangeClass -> executeChoice(context, "classchange", "job", command.classValue)
            is CharacterCommand.AllocateStat -> executeStat(context, command)
            is CharacterCommand.AllocateStats -> executeStats(context, command)
            is CharacterCommand.EquipItem -> executeChoice(context, "equip_item", "item_no", command.itemValue)
            is CharacterCommand.RemoveEquipment -> executeChoice(context, "remove", "spot", command.equipmentPart)
            is CharacterCommand.RemoveAllEquipment -> executeSimple(context, "remove_all")
            is CharacterCommand.SaveEquipmentPreset -> {
                CharacterEquipmentCommandRules.requirePresetSlot(command.slotNumber)
                executeSimple(context, "Equip_S_${command.slotNumber}")
            }
            is CharacterCommand.LoadEquipmentPreset -> {
                CharacterEquipmentCommandRules.requirePresetSlot(command.slotNumber)
                executeSimple(context, "Equip_L_${command.slotNumber}")
            }
        } ?: return rejected(
            "FORM_NOT_OBSERVED",
            "현재 HOF 페이지에서 이 기능을 확인하지 못해 실행하지 않았습니다.",
        )
        return CharacterCommandObservation.Applied(messages)
    }

    private fun executeIdentitySequence(
        context: CharacterCommandContext,
        requiredSubmitFields: List<String>,
        rememberContinuation: (TownRequestContinuation) -> Unit,
        applied: (List<CharacterCommandObservedIdentity>, java.time.Instant, List<String>) -> CharacterCommandObservation,
    ): CharacterCommandObservation = executor.executeResolvedFormSequenceWithFollowupProjected(
        accountId = context.accountId,
        pageUrl = requestFactory.characterPage(context.hofCharacterId).url,
        requiredSubmitFields = requiredSubmitFields,
        followupUrl = requestFactory.home().url,
        onFinalSubmissionUnconfirmed = { observedAt ->
            CharacterCommandObservation.IdentityAppliedRosterUnconfirmed(
                rosterObservedAt = observedAt,
                message = "HOF 최종 요청의 결과를 확인하지 못했습니다. 최신 캐릭터 목록을 확인해 주세요.",
            )
        },
    ) { result, followupHtml, _, _, rosterObservedAt, continuation ->
        rememberContinuation(continuation)
        val roster = followupHtml?.let { html -> runCatching { parseRoster(html) }.getOrDefault(emptyList()) }.orEmpty()
        if (roster.isEmpty()) {
            CharacterCommandObservation.IdentityAppliedRosterUnconfirmed(
                rosterObservedAt = rosterObservedAt,
                messages = result.messages,
            )
        } else {
            applied(roster, rosterObservedAt, result.messages)
        }
    } ?: rejected(
        "FORM_NOT_OBSERVED",
        "현재 HOF 페이지에서 캐릭터 확인 단계를 찾지 못해 실행하지 않았습니다.",
    )

    private fun observeRoster(accountId: Long): List<CharacterCommandObservedIdentity> =
        executor.loadProjectedWithoutCharacterRosterObservation(
            accountId,
            requestFactory.home().url,
        ) { html, _, _ -> parseRoster(html) }

    private fun parseRoster(html: String): List<CharacterCommandObservedIdentity> =
        rosterParser.parse(html).map {
            CharacterCommandObservedIdentity(it.id, it.name, it.job, it.level)
        }

    private fun refreshSnapshot(
        accountId: Long,
        hofCharacterId: String,
        continuation: TownRequestContinuation,
    ): Boolean = runCatching {
        executor.continueLoadProjected(
            continuation,
            requestFactory.characterPage(hofCharacterId).url,
        ) { html, _, _ ->
            snapshots.writeParsed(accountId, hofCharacterId, detailParser.parsePage(hofCharacterId, html))
        }
    }.isSuccess

    private fun executeRename(context: CharacterCommandContext, newName: String): List<String>? {
        if (newName.isBlank() || newName.length > 16) return null
        executeSimple(context, "rename") ?: return null
        val form = findForm(context, "namechange") ?: return null
        val field = form.editableFields.singleOrNull { it.inputName.equals("newname", true) } ?: return null
        return management.execute(
            context.accountId,
            context.hofCharacterId,
            TownActionRequest(form.actionId, values = listOf(TownFieldValue(field.id, newName))),
        ).messages
    }

    private fun executeStat(context: CharacterCommandContext, command: CharacterCommand.AllocateStat): List<String>? {
        return executeStats(
            context,
            CharacterCommand.AllocateStats(
                command.characterId,
                command.expectedRevision,
                mapOf(command.stat to command.amount),
            ),
        )
    }

    private fun executeStats(context: CharacterCommandContext, command: CharacterCommand.AllocateStats): List<String>? {
        if (command.amounts.isEmpty() || command.amounts.values.any { it !in 0..10_000 }) return null
        val form = findForm(context, "stup") ?: return null
        val selections = CharacterStat.entries.map { stat ->
            val amount = command.amounts[stat] ?: 0
            val candidate = form.candidates.singleOrNull {
                it.inputName.equals("up${stat.name}", true) && it.inputValue == amount.toString()
            } ?: return null
            TownActionSelection(candidate.id)
        }
        return management.execute(
            context.accountId,
            context.hofCharacterId,
            TownActionRequest(form.actionId, selections = selections),
        ).messages
    }

    private fun executeSimple(context: CharacterCommandContext, source: String): List<String>? {
        return executeSimpleSnapshot(context, source)?.messages
    }

    private fun executeSimpleSnapshot(context: CharacterCommandContext, source: String) =
        findForm(context, source)?.let { form ->
            management.execute(context.accountId, context.hofCharacterId, TownActionRequest(form.actionId))
        }

    private fun executeChoice(
        context: CharacterCommandContext,
        source: String,
        inputName: String,
        inputValue: String,
    ): List<String>? {
        val form = findForm(context, source) ?: return null
        val candidate = form.candidates.singleOrNull {
            it.inputName.equals(inputName, true) && it.inputValue == inputValue
        } ?: return null
        return management.execute(
            context.accountId,
            context.hofCharacterId,
            TownActionRequest(form.actionId, selections = listOf(TownActionSelection(candidate.id))),
        ).messages
    }

    private fun findForm(context: CharacterCommandContext, source: String): ParsedTownForm? =
        executor.load(context.accountId, requestFactory.characterPage(context.hofCharacterId).url)
            .forms.singleOrNull { it.submitSource.equals(source, true) }

    private fun rejected(code: String, message: String) =
        CharacterCommandObservation.Rejected(code, message)
}
