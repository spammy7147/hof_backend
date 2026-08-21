package app.spammy.hof.character.command

import app.spammy.hof.character.service.CharacterManagementService
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFieldValue
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import org.springframework.stereotype.Component

/** 의미 명령을 최신 HOF form에서 다시 관측한 opaque action ID로 변환하는 유일한 adapter. */
@Component
class HofCharacterCommandAdapter(
    private val executor: TownAuthenticatedExecutor,
    private val management: CharacterManagementService,
    private val requestFactory: HofRequestFactory,
) : CharacterCommandRemote {
    override fun <T> withSession(accountId: Long, operation: (CharacterCommandRemoteSession) -> T): T =
        executor.executeAccountSequence(accountId) {
            operation(CharacterCommandRemoteSession(::execute))
        }

    private fun execute(context: CharacterCommandContext, command: CharacterCommand): CharacterCommandObservation {
        val messages = when (command) {
                is CharacterCommand.Rename -> executeRename(context, command.newName)
                is CharacterCommand.Kick -> executeSimpleSnapshot(context, "byebye")?.messages
                is CharacterCommand.Knockback -> {
                    val snapshot = executeSimpleSnapshot(context, "knockback")
                        ?: return rejected(
                            "FORM_NOT_OBSERVED",
                            "현재 HOF 페이지에서 Knockback 확인 단계를 찾지 못해 실행하지 않았습니다.",
                        )
                    if (snapshot.identityResolutionRequired) {
                        return CharacterCommandObservation.IdentityResolutionRequired(
                            snapshot.identityCandidates.map {
                                CharacterCommandIdentityCandidate(
                                    it.hofCharacterId,
                                    it.name,
                                    it.job,
                                    it.level,
                                    it.matchingFields,
                                )
                            },
                            snapshot.messages.firstOrNull() ?: "새 캐릭터 연결을 선택해 주세요.",
                        )
                    }
                    snapshot.messages
                }
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
