package app.spammy.hof.automation.dto

/**
 * 홈 화면의 자동전투 카드를 새로 만들 때 쓰는 요청 DTO다.
 */
data class CreateAutomationProfileRequest(
    val name: String,
    val mode: String,
    val maps: List<AutomationProfileMapRequest>,
)

/**
 * 기존 자동전투 카드의 이름, 모드, 맵 설정, 활성 상태를 수정할 때 쓰는 요청 DTO다.
 */
data class UpdateAutomationProfileRequest(
    val name: String,
    val mode: String,
    val maps: List<AutomationProfileMapRequest>,
    val enabled: Boolean,
)

/** 자동전투 프로필에 포함할 맵, nullable 파티 프리셋, 실행 순서를 받는 요청 행이다. */
data class AutomationProfileMapRequest(
    val categoryId: String,
    val mapCode: String,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

/** 정적 맵 식별자와 검증된 파티 프리셋 FK를 실행 순서대로 내려주는 응답 행이다. */
data class AutomationProfileMapResponse(
    val categoryId: String,
    val mapCode: String,
    val partyPresetId: Long?,
    val executionOrder: Int,
)

/**
 * 저장된 자동전투 카드 한 개를 앱으로 내려주는 응답 DTO다.
 */
data class AutomationProfileResponse(
    val id: Long,
    val accountId: Long,
    val name: String,
    val mode: String,
    val maps: List<AutomationProfileMapResponse>,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
)
