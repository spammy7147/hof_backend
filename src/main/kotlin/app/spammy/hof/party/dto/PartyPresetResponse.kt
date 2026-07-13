package app.spammy.hof.party.dto

/**
 * 파티 프리셋 저장 요청에서 슬롯 하나의 캐릭터/패턴 선택 값을 표현한다.
 */
data class PartyPresetMemberRequest(
    val slotIndex: Int,
    val characterId: String?,
    val patternSlot: Int?,
)

/**
 * 새 파티 프리셋을 만들 때 앱이 보내는 요청 DTO다.
 */
data class CreatePartyPresetRequest(
    val name: String,
    val members: List<PartyPresetMemberRequest>,
)

/**
 * 기존 파티 프리셋을 수정할 때 앱이 보내는 요청 DTO다.
 */
data class UpdatePartyPresetRequest(
    val name: String,
    val members: List<PartyPresetMemberRequest>,
)

/**
 * 저장된 파티 프리셋의 슬롯 하나를 앱으로 내려주는 응답 DTO다.
 */
data class PartyPresetMemberResponse(
    val slotIndex: Int,
    val characterId: String?,
    val patternSlot: Int?,
)

/**
 * 캐릭터 탭의 프리셋 목록에 표시할 저장 파티 프리셋 응답 DTO다.
 */
data class PartyPresetResponse(
    val id: Long,
    val accountId: Long,
    val name: String,
    val members: List<PartyPresetMemberResponse>,
    val createdAt: String,
    val updatedAt: String,
)
