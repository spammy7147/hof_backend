package app.spammy.hof.character.command

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.identity.CharacterIdentityEvidence
import app.spammy.hof.character.identity.CharacterIdentityResolution
import app.spammy.hof.character.identity.CharacterIdentityResolver
import app.spammy.hof.character.repository.CharacterQueryRepository
import org.springframework.stereotype.Service

/** 의미 명령의 소유권·revision·최신 identity·원격 결과 확정을 하나의 수명주기로 처리한다. */
@Service
class CharacterCommandExecutor(
    private val characters: CharacterQueryRepository,
    private val automation: CharacterAutomationGate,
    private val remote: CharacterCommandRemote,
    private val identityResolver: CharacterIdentityResolver,
    private val identityProjection: CharacterCommandIdentityProjection,
) {
    fun preview(accountId: Long, command: CharacterCommand): CharacterCommandPreview {
        val character = characters.findByAccountIdAndId(accountId, command.characterId)
            ?: throw IllegalArgumentException("캐릭터를 찾지 못했습니다.")
        return CharacterCommandPreviewFactory.preview(command, character.name)
    }

    fun execute(accountId: Long, command: CharacterCommand): CharacterCommandResult {
        val initial = characters.findByAccountIdAndId(accountId, command.characterId)
            ?: return CharacterCommandResult.Rejected(command.characterId, "CHARACTER_NOT_FOUND", "캐릭터를 찾지 못했습니다.")
        validate(initial, command)?.let { return it }

        return automation.execute(
            accountId,
            unavailable = {
                CharacterCommandResult.RefreshRequired(
                    initial.id,
                    "현재 자동화 작업이 끝나기를 기다리고 있습니다. 잠시 후 다시 시도해 주세요.",
                )
            },
        ) {
            remote.withSession(accountId) { session ->
                executeAgainstLatest(accountId, command, session)
            }
        }
    }

    private fun executeAgainstLatest(
        accountId: Long,
        command: CharacterCommand,
        session: CharacterCommandRemoteSession,
    ): CharacterCommandResult {
        val current = characters.findByAccountIdAndId(accountId, command.characterId)
            ?: return CharacterCommandResult.Rejected(
                command.characterId,
                "CHARACTER_NOT_FOUND",
                "캐릭터를 찾지 못했습니다.",
            )
        validate(current, command)?.let { return it }
        return try {
            val observedRosterBefore = when (command) {
                is CharacterCommand.Kick,
                is CharacterCommand.Knockback,
                -> session.observeRoster().also { roster ->
                    validateObservedIdentity(current, command, roster)?.let { return it }
                }
                else -> emptyList()
            }
            val localHofIdsBefore = if (command is CharacterCommand.Knockback) {
                characters.findAllByAccountId(accountId).mapTo(linkedSetOf()) { it.hofCharacterId }
            } else {
                emptySet()
            }
            val context = CharacterCommandContext(accountId, current.id, current.hofCharacterId)
            project(
                accountId,
                current,
                observedRosterBefore.map(CharacterCommandObservedIdentity::toIdentityEvidence),
                localHofIdsBefore,
                session,
                session.execute(context, command),
            )
        } catch (error: IllegalArgumentException) {
            CharacterCommandResult.Rejected(
                current.id,
                "INVALID_COMMAND",
                error.message ?: "현재 실행할 수 없는 캐릭터 명령입니다.",
            )
        }
    }

    private fun project(
        accountId: Long,
        targetBefore: CharacterEntity,
        rosterBefore: List<CharacterIdentityEvidence>,
        localHofIdsBefore: Set<String>,
        session: CharacterCommandRemoteSession,
        observation: CharacterCommandObservation,
    ): CharacterCommandResult {
        val characterId = targetBefore.id
        return when (observation) {
            is CharacterCommandObservation.Applied -> completed(accountId, characterId, observation.messages)
            is CharacterCommandObservation.KickApplied -> recordKick(
                accountId,
                targetBefore,
                observation,
            )
            is CharacterCommandObservation.KnockbackApplied -> recordKnockback(
                accountId,
                targetBefore,
                rosterBefore,
                localHofIdsBefore,
                session,
                observation,
            )
            is CharacterCommandObservation.IdentityAppliedRosterUnconfirmed -> recordUnconfirmedIdentity(
                accountId,
                targetBefore,
                observation,
            )
            is CharacterCommandObservation.RefreshRequired ->
                CharacterCommandResult.RefreshRequired(characterId, observation.message)
            is CharacterCommandObservation.Rejected ->
                CharacterCommandResult.Rejected(characterId, observation.code, observation.message)
        }
    }

    private fun recordKick(
        accountId: Long,
        targetBefore: CharacterEntity,
        observation: CharacterCommandObservation.KickApplied,
    ): CharacterCommandResult {
        val rosterIds = observation.rosterAfter.mapTo(linkedSetOf()) { it.hofCharacterId }
        if (rosterIds.isEmpty()) {
            return CharacterCommandResult.RefreshRequired(
                targetBefore.id,
                "Kick 후 HOF 캐릭터 목록을 확인하지 못했습니다. 새로고침 후 다시 확인해 주세요.",
            )
        }
        if (targetBefore.hofCharacterId in rosterIds) {
            return CharacterCommandResult.RefreshRequired(
                targetBefore.id,
                "Kick 결과가 HOF 캐릭터 목록에서 아직 확인되지 않았습니다. 잠시 후 다시 확인해 주세요.",
            )
        }
        return when (
            identityProjection.recordKick(
                accountId,
                targetBefore.id,
                targetBefore.hofCharacterId,
                rosterIds,
                observation.rosterObservedAt,
            )
        ) {
            CharacterCommandIdentityProjectionResult.Applied -> completed(accountId, targetBefore.id, observation.messages)
            else -> changedDuringProjection(targetBefore.id)
        }
    }

    private fun recordKnockback(
        accountId: Long,
        targetBefore: CharacterEntity,
        rosterBefore: List<CharacterIdentityEvidence>,
        localHofIdsBefore: Set<String>,
        session: CharacterCommandRemoteSession,
        observation: CharacterCommandObservation.KnockbackApplied,
    ): CharacterCommandResult {
        val rosterAfter = observation.rosterAfter.map(CharacterCommandObservedIdentity::toIdentityEvidence)
        if (rosterAfter.isEmpty()) {
            return CharacterCommandResult.RefreshRequired(
                targetBefore.id,
                "Knockback 후 HOF 캐릭터 목록을 확인하지 못했습니다. 새로고침 후 다시 확인해 주세요.",
            )
        }
        val rosterIds = rosterAfter.mapTo(linkedSetOf()) { it.hofCharacterId }
        return when (val resolution = identityResolver.resolveKnockback(targetBefore.toIdentityEvidence(), rosterBefore, rosterAfter)) {
            is CharacterIdentityResolution.Confirmed -> {
                when (
                    identityProjection.recordConfirmedKnockback(
                        accountId,
                        targetBefore.id,
                        targetBefore.hofCharacterId,
                        resolution.replacement.hofCharacterId,
                        localHofIdsBefore,
                        rosterIds,
                        observation.rosterObservedAt,
                    )
                ) {
                    CharacterCommandIdentityProjectionResult.Conflict -> return changedDuringProjection(targetBefore.id)
                    CharacterCommandIdentityProjectionResult.Occupied -> return CharacterCommandResult.IdentityResolutionRequired(
                        targetBefore.id,
                        observation.rosterAfter.map { observed ->
                            observed.toCandidate(targetBefore.toIdentityEvidence().matchingFields(observed.toIdentityEvidence()))
                        },
                        "새 HOF ID가 다른 캐릭터 기록과 겹칩니다. 연결할 캐릭터를 확인해 주세요.",
                    )
                    CharacterCommandIdentityProjectionResult.Applied -> Unit
                }
                val refreshSucceeded = session.refreshSnapshot(resolution.replacement.hofCharacterId)
                completed(
                    accountId,
                    targetBefore.id,
                    if (refreshSucceeded) observation.messages else observation.messages +
                        "새 HOF ID 연결은 완료됐지만 상세 정보는 새로고침이 필요합니다.",
                )
            }
            is CharacterIdentityResolution.Candidates -> {
                if (recordObservedRoster(accountId, targetBefore, rosterIds, observation.rosterObservedAt) != null) {
                    return changedDuringProjection(targetBefore.id)
                }
                CharacterCommandResult.IdentityResolutionRequired(
                    targetBefore.id,
                    observation.rosterAfter.map { observed ->
                        val candidate = resolution.candidates.singleOrNull {
                            it.character.hofCharacterId == observed.hofCharacterId
                        }
                        observed.toCandidate(candidate?.matchingFields.orEmpty())
                    },
                    observation.messages.firstOrNull() ?: "새 캐릭터 연결을 선택해 주세요.",
                )
            }
            is CharacterIdentityResolution.Unresolved -> {
                if (recordObservedRoster(accountId, targetBefore, rosterIds, observation.rosterObservedAt) != null) {
                    return changedDuringProjection(targetBefore.id)
                }
                CharacterCommandResult.IdentityResolutionRequired(
                    targetBefore.id,
                    observation.rosterAfter.map { it.toCandidate() },
                    observation.messages.firstOrNull() ?: "새 캐릭터 연결을 선택해 주세요.",
                )
            }
        }
    }

    private fun recordUnconfirmedIdentity(
        accountId: Long,
        targetBefore: CharacterEntity,
        observation: CharacterCommandObservation.IdentityAppliedRosterUnconfirmed,
    ): CharacterCommandResult {
        val projected = identityProjection.recordUnconfirmedIdentityAction(
            accountId,
            targetBefore.id,
            targetBefore.hofCharacterId,
            observation.rosterObservedAt,
        )
        return CharacterCommandResult.RefreshRequired(
            targetBefore.id,
            if (projected == CharacterCommandIdentityProjectionResult.Applied) {
                observation.message
            } else {
                "처리 중 캐릭터 연결 상태가 변경되었습니다. 최신 목록을 확인해 주세요."
            },
        )
    }

    private fun validateObservedIdentity(
        current: CharacterEntity,
        command: CharacterCommand,
        roster: List<CharacterCommandObservedIdentity>,
    ): CharacterCommandResult? {
        if (roster.isEmpty()) {
            return CharacterCommandResult.RefreshRequired(
                current.id,
                "작업 직전 HOF 캐릭터 목록을 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.",
            )
        }
        val observed = roster.singleOrNull { it.hofCharacterId == current.hofCharacterId }
            ?: return CharacterCommandResult.RefreshRequired(
                current.id,
                "HOF 캐릭터 배치가 변경되었습니다. 최신 목록을 확인한 뒤 다시 시도해 주세요.",
            )
        val confirmationName = when (command) {
            is CharacterCommand.Kick -> command.confirmationName
            is CharacterCommand.Knockback -> command.confirmationName
            else -> error("identity 확인이 필요하지 않은 명령입니다.")
        }
        if (observed.name != confirmationName || observed.name != current.name) {
            return CharacterCommandResult.Rejected(
                current.id,
                "REMOTE_IDENTITY_MISMATCH",
                "HOF에서 확인한 캐릭터가 선택한 캐릭터와 일치하지 않아 실행하지 않았습니다.",
            )
        }
        if (roster.count { it.name == current.name } != 1) {
            return CharacterCommandResult.RefreshRequired(
                current.id,
                "HOF에 같은 이름의 캐릭터가 있어 대상을 안전하게 확정하지 못했습니다.",
            )
        }
        if (current.job.isBlank() || current.level == null || observed.job.isBlank() || observed.level == null) {
            return CharacterCommandResult.RefreshRequired(
                current.id,
                "캐릭터의 직업과 레벨을 확인하지 못해 위험 작업을 실행하지 않았습니다.",
            )
        }
        if (observed.job != current.job || observed.level != current.level) {
            return CharacterCommandResult.Rejected(
                current.id,
                "REMOTE_IDENTITY_MISMATCH",
                "HOF에서 확인한 캐릭터의 직업 또는 레벨이 선택한 캐릭터와 일치하지 않습니다.",
            )
        }
        return null
    }

    private fun recordObservedRoster(
        accountId: Long,
        targetBefore: CharacterEntity,
        rosterIds: Set<String>,
        rosterObservedAt: java.time.Instant,
    ): CharacterCommandIdentityProjectionResult? =
        identityProjection.recordObservedRoster(
            accountId,
            targetBefore.id,
            targetBefore.hofCharacterId,
            rosterIds,
            rosterObservedAt,
        ).takeUnless { it == CharacterCommandIdentityProjectionResult.Applied }

    private fun changedDuringProjection(characterId: Long) = CharacterCommandResult.RefreshRequired(
        characterId,
        "처리 중 캐릭터 연결 상태가 변경되었습니다. 최신 목록을 확인해 주세요.",
    )

    private fun completed(accountId: Long, characterId: Long, messages: List<String>): CharacterCommandResult =
        characters.findByAccountIdAndId(accountId, characterId)
            ?.let { CharacterCommandResult.Completed(characterId, it.updatedAt, messages) }
            ?: CharacterCommandResult.RefreshRequired(
                characterId,
                "명령은 전송되었지만 캐릭터 상태를 다시 확인해야 합니다.",
            )

    private fun validate(
        character: CharacterEntity,
        command: CharacterCommand,
    ): CharacterCommandResult? {
        if (character.lifecycle != CharacterLifecycle.ACTIVE) {
            return CharacterCommandResult.Rejected(
                command.characterId,
                "CHARACTER_NOT_ACTIVE",
                "현재 HOF에서 사용 중인 캐릭터가 아닙니다.",
            )
        }
        // Kick은 최신 이름을 다시 확인하고 HOF의 다단계 확인을 거치므로, 배경 동기화가
        // updatedAt만 갱신한 경우에는 사용자의 명시적 확인을 무효화하지 않는다.
        if (command !is CharacterCommand.Kick && character.updatedAt != command.expectedRevision) {
            return CharacterCommandResult.Conflict(command.characterId, command.expectedRevision, character.updatedAt)
        }
        val confirmationName = when (command) {
            is CharacterCommand.Kick -> command.confirmationName
            is CharacterCommand.Knockback -> command.confirmationName
            else -> null
        }
        if (confirmationName != null && confirmationName != character.name) {
            return CharacterCommandResult.Rejected(
                command.characterId,
                "CONFIRMATION_MISMATCH",
                "캐릭터 이름 확인이 일치하지 않습니다.",
            )
        }
        return null
    }
}

private fun CharacterEntity.toIdentityEvidence() = CharacterIdentityEvidence(hofCharacterId, name, job, level)

private fun CharacterCommandObservedIdentity.toIdentityEvidence() = CharacterIdentityEvidence(hofCharacterId, name, job, level)

private fun CharacterCommandObservedIdentity.toCandidate(matchingFields: Set<String> = emptySet()) =
    CharacterCommandIdentityCandidate(hofCharacterId, name, job, level, matchingFields)

private fun CharacterIdentityEvidence.matchingFields(other: CharacterIdentityEvidence): Set<String> = buildSet {
    if (name.isNotBlank() && name == other.name) add("name")
    if (job.isNotBlank() && job == other.job) add("job")
    if (level != null && level == other.level) add("level")
}
