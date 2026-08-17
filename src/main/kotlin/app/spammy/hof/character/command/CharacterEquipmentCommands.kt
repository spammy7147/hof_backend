package app.spammy.hof.character.command

object CharacterEquipmentCommandRules {
    fun requirePresetSlot(slotNumber: Int) {
        require(slotNumber in 1..2) { "장비 저장 슬롯은 1번 또는 2번만 사용할 수 있습니다." }
    }

    fun requireExactCandidate(itemValue: String, observedValues: List<String>): String {
        require(itemValue.isNotBlank()) { "장비 식별자가 비어 있습니다." }
        require(observedValues.count { it == itemValue } == 1) { "현재 장착 가능한 장비와 정확히 일치하지 않습니다." }
        return itemValue
    }
}
