package app.spammy.hof.character.command

data class CharacterCommandPreview(
    val dangerous: Boolean,
    val title: String,
    val confirmationText: String? = null,
)

object CharacterCommandPreviewFactory {
    fun preview(command: CharacterCommand, characterName: String): CharacterCommandPreview = when (command) {
        is CharacterCommand.Rename -> CharacterCommandPreview(true, "캐릭터 이름 변경", characterName)
        is CharacterCommand.Kick -> CharacterCommandPreview(true, "캐릭터 추방", characterName)
        is CharacterCommand.Knockback -> CharacterCommandPreview(true, "캐릭터 맨 뒤로 이동", characterName)
        is CharacterCommand.ChangeClass -> CharacterCommandPreview(true, "전직", characterName)
        else -> CharacterCommandPreview(false, "캐릭터 설정 변경")
    }
}
