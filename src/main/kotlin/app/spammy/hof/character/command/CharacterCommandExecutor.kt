package app.spammy.hof.character.command

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterQueryRepository
import org.springframework.stereotype.Service

/** 의미 명령의 소유권·revision·최신 identity·원격 결과 확정을 하나의 수명주기로 처리한다. */
@Service
class CharacterCommandExecutor(
    private val characters: CharacterQueryRepository,
    private val automation: CharacterAutomationGate,
    private val remote: CharacterCommandRemote,
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
        val context = CharacterCommandContext(accountId, current.id, current.hofCharacterId)
        return try {
            project(accountId, current.id, session.execute(context, command))
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
        characterId: Long,
        observation: CharacterCommandObservation,
    ): CharacterCommandResult = when (observation) {
        is CharacterCommandObservation.Applied -> {
            characters.findByAccountIdAndId(accountId, characterId)
                ?.let { CharacterCommandResult.Completed(characterId, it.updatedAt, observation.messages) }
                ?: CharacterCommandResult.RefreshRequired(
                    characterId,
                    "명령은 전송되었지만 캐릭터 상태를 다시 확인해야 합니다.",
                )
        }
        is CharacterCommandObservation.IdentityResolutionRequired ->
            CharacterCommandResult.IdentityResolutionRequired(characterId, observation.candidates, observation.message)
        is CharacterCommandObservation.RefreshRequired ->
            CharacterCommandResult.RefreshRequired(characterId, observation.message)
        is CharacterCommandObservation.Rejected ->
            CharacterCommandResult.Rejected(characterId, observation.code, observation.message)
    }

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
