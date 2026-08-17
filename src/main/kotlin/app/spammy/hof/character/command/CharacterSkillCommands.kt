package app.spammy.hof.character.command

object CharacterSkillCommandRules {
    fun requireLearnable(skillValue: String, observedValues: List<String>): String {
        require(skillValue.isNotBlank() && observedValues.count { it == skillValue } == 1) {
            "현재 배울 수 있는 스킬과 정확히 일치하지 않습니다."
        }
        return skillValue
    }
}
