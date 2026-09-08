package app.spammy.hof.character.command

import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.EquipmentCandidateScriptParser
import app.spammy.hof.external.parser.HofHtmlParser
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
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
                        afterEquipmentChange: ((String, String) -> Unit)?,
                    ): CharacterCommandObservation = this@HofCharacterCommandAdapter.execute(context, command, afterEquipmentChange) {
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
        afterEquipmentChange: ((String, String) -> Unit)?,
        rememberContinuation: (TownRequestContinuation) -> Unit,
    ): CharacterCommandObservation {
        require(afterEquipmentChange == null || command is CharacterCommand.EquipItem || command is CharacterCommand.RemoveAllEquipment)
        val messages = when (command) {
            is CharacterCommand.Rename -> executeRename(context, command.newName)
            is CharacterCommand.Kick -> return executeIdentitySequence(
                context,
                listOf("byebye", "byebye2", "kick"),
                rememberContinuation,
            ) { roster, observedAt, messages -> CharacterCommandObservation.KickApplied(roster, observedAt, messages) }
            is CharacterCommand.Knockback -> return executeIdentitySequence(
                context,
                listOf("knockback", "knockback2"),
                rememberContinuation,
            ) { roster, observedAt, messages -> CharacterCommandObservation.KnockbackApplied(roster, observedAt, messages) }
            is CharacterCommand.Pray -> executeSimple(context, "pray")
            is CharacterCommand.PrepareItems -> executePrepareItems(context)
            is CharacterCommand.UseItem -> executeItem(context, command.itemValue)
            is CharacterCommand.LearnSkill -> executeChoice(context, "learnskill", "newskill", command.skillValue)
            is CharacterCommand.ChangeClass -> executeChoice(context, "classchange", "job", command.classValue)
            is CharacterCommand.AllocateStat -> executeStat(context, command)
            is CharacterCommand.AllocateStats -> executeStats(context, command)
            is CharacterCommand.EquipItem -> executeEquipment(context, command.itemValue, afterEquipmentChange)
            is CharacterCommand.RemoveEquipment -> executeChoice(context, "remove", "spot", command.equipmentPart)
            is CharacterCommand.RemoveAllEquipment -> executeSimpleSnapshot(context, "remove_all", afterEquipmentChange)
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
        return executor.executeResolvedTwoStepProjected(
            accountId = context.accountId,
            pageUrl = characterUrl(context),
            requiredEntrySubmitField = "rename",
            requiredFinalSubmitField = "namechange",
            entryAction = { page -> page.semanticForm("rename")?.let { TownActionRequest(it.actionId) } },
            finalAction = { page ->
                page.semanticForm("namechange")?.let { form ->
                    form.editableFields.singleOrNull { it.inputName.equals("newname", true) }
                        ?.let { field ->
                            TownActionRequest(form.actionId, values = listOf(TownFieldValue(field.id, newName)))
                        }
                }
            },
        ) { html, _, result, _ ->
            projectSnapshot(context, html)
            result.messages
        }
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
        return executeProjected(context) { html, page ->
            val form = page.semanticForm("stup") ?: return@executeProjected null
            val statusPoints = detailParser.parsePage(context.hofCharacterId, html).snapshot.stats.statusPoints
                ?: return@executeProjected null
            CharacterStatCommandRules.requireAllocation(command.amounts, statusPoints)
            val selections = CharacterStat.entries.map { stat ->
                form.uniqueCandidate("up${stat.name}", (command.amounts[stat] ?: 0).toString())
                    ?.let { TownActionSelection(it.id) }
                    ?: return@executeProjected null
            }
            TownActionRequest(form.actionId, selections = selections)
        }
    }

    private fun executeSimple(context: CharacterCommandContext, source: String): List<String>? {
        return executeSimpleSnapshot(context, source)
    }

    private fun executeSimpleSnapshot(context: CharacterCommandContext, source: String, afterSubmit: ((String, String) -> Unit)? = null): List<String>? =
        executeProjected(context, afterSubmit) { _, page ->
            page.semanticForm(source)?.let { form -> TownActionRequest(form.actionId) }
        }

    private fun executeChoice(
        context: CharacterCommandContext,
        source: String,
        inputName: String,
        inputValue: String,
    ): List<String>? {
        return executeProjected(context) { _, page ->
            val form = page.semanticForm(source) ?: return@executeProjected null
            val observedValues = form.candidates
                .filter { it.inputName.equals(inputName, true) }
                .map { it.inputValue }
            val exactValue = inputValue.takeIf { value -> observedValues.count { it == value } == 1 }
                ?: return@executeProjected null
            val candidate = form.uniqueCandidate(inputName, exactValue) ?: return@executeProjected null
            TownActionRequest(form.actionId, selections = listOf(TownActionSelection(candidate.id)))
        }
    }

    private fun executeEquipment(context: CharacterCommandContext, itemValue: String, afterSubmit: ((String, String) -> Unit)? = null): List<String>? = try {
        executor.executeMaterializedResolvedProjectedWithScalars(
            accountId = context.accountId,
            pageUrl = characterUrl(context),
            requiredScalarFields = emptySet(),
            requiredSubmitField = "equip_item",
            requiredSyntheticFields = setOf("list_type"),
            requiredReplacedFields = setOf("item_no"),
            allowedAbsentRawReplacedFields = setOf("item_no"),
            materialize = { html, finalUrl -> materializeEquipmentForm(html, finalUrl, itemValue) },
            resolve = { _, _, page ->
                val form = page.semanticForm("equip_item") ?: throw FormNotObserved()
                val candidate = form.uniqueCandidate("item_no", itemValue) ?: throw FormNotObserved()
                TownActionRequest(form.actionId, selections = listOf(TownActionSelection(candidate.id))) to emptyMap()
            },
        ) { html, finalUrl, result, _ ->
            afterSubmit?.invoke(html, finalUrl)
            projectSnapshot(context, html)
            result.messages
        }
    } catch (_: FormNotObserved) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun materializeEquipmentForm(html: String, finalUrl: String, itemValue: String): String {
        val document = HofHtmlParser.parse(html, finalUrl)
        val candidates = EquipmentCandidateScriptParser.parseEquipmentCatalog(document)
        val exactValue = CharacterEquipmentCommandRules.requireExactCandidate(
            itemValue,
            candidates.map { it.value },
        )
        val candidate = candidates.single { it.value == exactValue }
        require(candidate.typeCode.isNotBlank() && candidate.typeCode.length <= 80) {
            "현재 장비 분류를 안전하게 확인하지 못했습니다."
        }
        val typeSelectors = document.select("select[name=type_equip]")
        if (typeSelectors.size != 1 ||
            typeSelectors.single().select("option").count { it.attr("value") == candidate.typeCode } != 1
        ) throw FormNotObserved()
        val form = document.select("form").singleOrNull { observed ->
            observed.select("input[type=submit][name=equip_item], button[type=submit][name=equip_item]").size == 1
        } ?: throw FormNotObserved()
        val list = form.selectFirst("#list0") ?: throw FormNotObserved()
        list.empty()
        list.appendElement("input")
            .attr("type", "radio")
            .attr("name", "item_no")
            .attr("value", candidate.value)
        list.appendElement("input")
            .attr("type", "hidden")
            .attr("name", "list_type")
            .attr("value", candidate.typeCode)
        return document.outerHtml()
    }

    private fun executePrepareItems(context: CharacterCommandContext): List<String>? = try {
        executor.executeProjected(
            accountId = context.accountId,
            pageUrl = characterUrl(context),
            resolveAction = { _, _, page ->
                page.semanticForm("showreset")?.let { TownActionRequest(it.actionId) }
                    ?: throw FormNotObserved()
            },
        ) { html, finalUrl, result, page ->
            page.semanticForm("resetVarious") ?: throw FormNotObserved()
            val document = HofHtmlParser.parse(html, finalUrl)
            val resetForms = document.select("form").filter { form ->
                form.select("input[type=submit][name=resetVarious], button[type=submit][name=resetVarious]").size == 1
            }
            if (resetForms.singleOrNull()?.select("select[name=itemUse]")?.size != 1) throw FormNotObserved()
            val candidates = EquipmentCandidateScriptParser.parse(document)
                .filter { it.typeCode == RESET_ITEM_TYPE }
            snapshots.writeEquipmentCandidateSubset(
                context.accountId,
                context.hofCharacterId,
                candidates,
                setOf(RESET_ITEM_TYPE),
            )
            result.messages
        }
    } catch (_: FormNotObserved) {
        null
    }

    private fun executeItem(context: CharacterCommandContext, itemValue: String): List<String>? =
        executor.executeResolvedDirectOrTwoStepProjected(
            accountId = context.accountId,
            pageUrl = characterUrl(context),
            requiredDirectSubmitField = "use_char_item",
            directAction = { page ->
                page.semanticForm("use_char_item")?.let { form ->
                    form.uniqueCandidate("item_no", itemValue)?.let { candidate ->
                        TownActionRequest(form.actionId, selections = listOf(TownActionSelection(candidate.id)))
                    }
                }
            },
            requiredEntrySubmitField = "showreset",
            requiredFinalSubmitField = "resetVarious",
            entryAction = { page -> page.semanticForm("showreset")?.let { TownActionRequest(it.actionId) } },
            finalAction = { page ->
                page.semanticForm("resetVarious")?.let { form ->
                    form.uniqueCandidate("itemUse", itemValue)?.let { candidate ->
                        TownActionRequest(
                            form.actionId,
                            selections = listOf(TownActionSelection(candidate.id)),
                        )
                    }
                }
            },
        ) { html, _, result, _ ->
            projectSnapshot(context, html)
            result.messages
        }

    private fun executeProjected(
        context: CharacterCommandContext,
        afterSubmit: ((String, String) -> Unit)? = null,
        resolve: (html: String, page: ParsedTownPage) -> TownActionRequest?,
    ): List<String>? = try {
        executor.executeProjected(
            accountId = context.accountId,
            pageUrl = characterUrl(context),
            resolveAction = { html, _, page -> resolve(html, page) ?: throw FormNotObserved() },
        ) { html, finalUrl, result, _ ->
            afterSubmit?.invoke(html, finalUrl)
            projectSnapshot(context, html)
            result.messages
        }
    } catch (_: FormNotObserved) {
        null
    }

    private fun projectSnapshot(context: CharacterCommandContext, html: String) {
        snapshots.writeParsed(
            context.accountId,
            context.hofCharacterId,
            detailParser.parsePage(context.hofCharacterId, html),
        )
    }

    private fun characterUrl(context: CharacterCommandContext): String =
        requestFactory.characterPage(context.hofCharacterId).url

    private fun ParsedTownPage.semanticForm(source: String): ParsedTownForm? =
        forms.singleOrNull { it.submitSource.equals(source, true) }

    private fun ParsedTownForm.uniqueCandidate(inputName: String, inputValue: String) =
        candidates.singleOrNull {
            it.inputName.equals(inputName, true) && it.inputValue == inputValue
        }

    private fun rejected(code: String, message: String) =
        CharacterCommandObservation.Rejected(code, message)

    private class FormNotObserved : RuntimeException()

    private companion object {
        const val RESET_ITEM_TYPE = "resetitem"
    }
}
