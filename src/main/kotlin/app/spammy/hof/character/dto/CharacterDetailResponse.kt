package app.spammy.hof.character.dto

/**
 * 캐릭터 상세 화면에 필요한 모든 파싱 정보를 담아 앱으로 내려주는 응답 DTO다.
 */
data class CharacterDetailResponse(
    val id: Long,
    val hofCharacterId: String,
    val name: String,
    val job: String,
    val level: Int?,
    val patternSlotCount: Int,
    val imageUrl: String?,
    val statusLines: List<String>,
    val patternSlots: List<CharacterPatternSlotResponse>,
    val stats: CharacterStatsResponse,
    val actionPatterns: List<CharacterActionPatternResponse>,
    val positionGuard: CharacterPositionGuardResponse,
    val equipment: List<CharacterEquipmentResponse>,
    val learnedSkills: List<CharacterSkillResponse>,
    val learnableSkills: List<CharacterSkillResponse>,
)

/**
 * 캐릭터 상세 페이지의 저장 패턴 슬롯 한 칸을 표현한다.
 */
data class CharacterPatternSlotResponse(
    val slot: String = "",
    val label: String = "",
    val canLoad: Boolean = false,
)

/**
 * 캐릭터의 주요 전투 스탯을 앱 표시용으로 묶은 DTO다.
 */
data class CharacterStatsResponse(
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
)

/**
 * 캐릭터 상세 페이지의 행동 패턴 한 줄을 표현한다.
 */
data class CharacterActionPatternResponse(
    val index: Int = 0,
    val judge: String = "",
    val judgeText: String = "",
    val quantity: String = "",
    val quantityText: String = "",
    val skill: String = "",
    val skillText: String = "",
)

/**
 * 캐릭터의 위치/가드 설정 영역을 표현한다.
 */
data class CharacterPositionGuardResponse(
    val positions: List<CharacterPositionChoiceResponse> = emptyList(),
    val selectedPosition: String = "",
    val guardValue: String = "",
    val guardText: String = "",
)

/**
 * 위치 선택지 하나의 값과 선택 여부를 표현한다.
 */
data class CharacterPositionChoiceResponse(
    val value: String = "",
    val checked: Boolean = false,
)

/**
 * 장비 한 칸의 이름, 부위, 아이콘, 설명을 표현한다.
 */
data class CharacterEquipmentResponse(
    val slot: String = "",
    val part: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val description: String = "",
    val checked: Boolean = false,
)

/**
 * 캐릭터가 배운 스킬 또는 배울 수 있는 스킬 한 개를 표현한다.
 */
data class CharacterSkillResponse(
    val value: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val category: String = "",
)
