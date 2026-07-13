package app.spammy.hof.external.model

/**
 * HOF 캐릭터 상세 페이지에서 파싱한 저장 패턴 슬롯 정보다.
 */
data class HofPatternSlot(
    val slot: String,
    val label: String = "",
    val canLoad: Boolean = false,
)
