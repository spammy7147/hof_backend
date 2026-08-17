package app.spammy.hof.external.model

/**
 * HOF 캐릭터 목록/상세 HTML에서 파싱한 캐릭터 스냅샷 모델이다.
 */
data class HofCharacter(
    val id: String,
    val name: String = "",
    val level: Int? = null,
    val job: String = "",
    val patternSlots: List<HofPatternSlot> = emptyList(),
    val imageUrl: String = "",
    val statusLines: List<String> = emptyList(),
    val stats: HofCharacterStats = HofCharacterStats(),
    val statusEffects: List<HofStatusEffect> = emptyList(),
    val faith: HofFaith? = null,
    val selectedPatternNumber: String = "",
    val actionPatterns: List<HofActionPatternRow> = emptyList(),
    val patternOptions: List<HofPatternOption> = emptyList(),
    val positionGuard: HofPositionGuard = HofPositionGuard(),
    val equipment: List<HofEquipment> = emptyList(),
    val equipmentCandidates: List<HofEquipmentCandidate> = emptyList(),
    val learnedSkills: List<HofSkill> = emptyList(),
    val learnableSkills: List<HofSkill> = emptyList(),
    /** HOF 홈 roster에 나타난 0-based 원본 순서. 상세 페이지만 파싱한 경우에는 알 수 없다. */
    val rosterOrder: Int? = null,
)
