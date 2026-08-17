package app.spammy.hof.external.model

/**
 * HOF 캐릭터 상세 HTML에서 파싱한 주요 스탯 묶음이다.
 */
data class HofCharacterStats(
    val statusPoints: Int? = null,
    val skillPoints: Int? = null,
    val atk: Int? = null,
    val matk: Int? = null,
    val defBase: Int? = null,
    val defBonus: Int? = null,
    val mdefBase: Int? = null,
    val mdefBonus: Int? = null,
    val handleUsed: Int? = null,
    val handleMax: Int? = null,
    val costUsed: Int? = null,
    val costMax: Int? = null,
    val expCurrent: Long? = null,
    val expMax: Long? = null,
    val expMaxed: Boolean? = null,
    val hpBase: Int? = null,
    val hpBonus: Int? = null,
    val spBase: Int? = null,
    val spBonus: Int? = null,
    val strReal: Int? = null,
    val strBonus: Int? = null,
    val intReal: Int? = null,
    val intBonus: Int? = null,
    val dexReal: Int? = null,
    val dexBonus: Int? = null,
    val spdReal: Int? = null,
    val spdBonus: Int? = null,
    val lukReal: Int? = null,
    val lukBonus: Int? = null,
    val descriptions: Map<String, String> = emptyMap(),
)

data class HofStatusEffect(
    val type: String,
    val name: String,
    val valueText: String,
    val description: String = "",
    val active: Boolean? = null,
)

data class HofFaith(val godName: String, val current: Long, val max: Long)

data class HofPatternOption(
    val type: String,
    val value: String,
    val label: String,
    val category: String = "",
)

data class HofEquipmentCandidate(
    val value: String,
    val typeCode: String,
    val name: String,
    val iconUrl: String = "",
    val description: String = "",
    val quantity: Int? = null,
)

/**
 * HOF 캐릭터 상세 HTML의 행동 패턴 한 줄이다.
 */
data class HofActionPatternRow(
    val index: Int,
    val judge: String = "",
    val judgeText: String = "",
    val quantity: String = "",
    val quantityText: String = "",
    val skill: String = "",
    val skillText: String = "",
)

/**
 * HOF 캐릭터 위치 선택지 하나를 표현한다.
 */
data class HofPositionChoice(
    val value: String,
    val checked: Boolean,
)

/**
 * HOF 캐릭터의 위치/가드 설정 영역을 표현한다.
 */
data class HofPositionGuard(
    val positions: List<HofPositionChoice> = emptyList(),
    val selectedPosition: String = "",
    val guardValue: String = "",
    val guardText: String = "",
)

/**
 * HOF 캐릭터가 장착 중인 장비 한 칸을 표현한다.
 */
data class HofEquipment(
    val slot: String = "",
    val part: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val description: String = "",
    val checked: Boolean = false,
)

/**
 * HOF 캐릭터가 배웠거나 배울 수 있는 스킬 한 개를 표현한다.
 */
data class HofSkill(
    val value: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val category: String = "",
    val targetText: String = "",
    val scopeText: String = "",
    val spCost: Int? = null,
    val multiplierText: String = "",
    val description: String = "",
)
