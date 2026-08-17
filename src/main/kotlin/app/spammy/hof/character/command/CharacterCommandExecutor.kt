package app.spammy.hof.character.command

import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterQueryRepository
import org.springframework.stereotype.Service

/** 소유권·안정 ID·revision·자동화 일시정지를 한 번 검증한 뒤 HOF adapter를 호출하는 명령 모듈. */
@Service
class CharacterCommandExecutor(
    private val characters: CharacterQueryRepository,
    private val automation: CharacterAutomationCommandBridge,
    private val adapter: CharacterCommandAdapter,
) {
    fun preview(accountId: Long, command: CharacterCommand): CharacterCommandPreview {
        val character = characters.findByAccountIdAndId(accountId, command.characterId)
            ?: throw IllegalArgumentException("캐릭터를 찾지 못했습니다.")
        return CharacterCommandPreviewFactory.preview(command, character.name)
    }

    fun execute(accountId: Long, command: CharacterCommand): CharacterCommandResult {
        val character = characters.findByAccountIdAndId(accountId, command.characterId)
            ?: return CharacterCommandResult.Rejected(command.characterId, "CHARACTER_NOT_FOUND", "캐릭터를 찾지 못했습니다.")
        if (character.lifecycle != CharacterLifecycle.ACTIVE) {
            return CharacterCommandResult.Rejected(command.characterId, "CHARACTER_NOT_ACTIVE", "현재 HOF에서 사용 중인 캐릭터가 아닙니다.")
        }
        if (character.updatedAt != command.expectedRevision) {
            return CharacterCommandResult.Conflict(command.characterId, command.expectedRevision, character.updatedAt)
        }
        val context = CharacterCommandContext(accountId, character.id, character.hofCharacterId)
        return automation.execute(accountId, character.id) {
            try {
                adapter.execute(context, command)
            } catch (error: IllegalArgumentException) {
                CharacterCommandResult.Rejected(
                    character.id,
                    "INVALID_COMMAND",
                    error.message ?: "현재 실행할 수 없는 캐릭터 명령입니다.",
                )
            }
        }
    }
}
