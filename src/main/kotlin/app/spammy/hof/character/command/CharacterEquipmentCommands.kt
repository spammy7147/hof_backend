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

object CharacterStatCommandRules {
    fun requireAllocation(amounts: Map<CharacterStat, Int>, observedStatusPoints: Int) {
        require(amounts.isNotEmpty() && amounts.values.any { it > 0 }) {
            "배분할 스탯 포인트를 입력해 주세요."
        }
        require(amounts.values.all { it >= 0 }) { "스탯 포인트는 음수로 배분할 수 없습니다." }
        require(observedStatusPoints >= 0 && amounts.values.sumOf(Int::toLong) <= observedStatusPoints.toLong()) {
            "보유한 Status Point보다 많이 배분할 수 없습니다."
        }
    }
}
