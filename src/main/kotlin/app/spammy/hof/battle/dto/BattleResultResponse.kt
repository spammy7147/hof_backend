package app.spammy.hof.battle.dto

import app.spammy.hof.external.model.HofBattleLoot
import app.spammy.hof.external.model.HofBattleResult
import app.spammy.hof.external.model.HofBattleSide
import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * 전투 실행 직후 앱으로 내려주는 전체 전투 결과 응답 DTO다.
 *
 * 3회 전투처럼 여러 결과가 생긴 경우 rounds에 각 회차 결과를 함께 담는다.
 */
data class BattleResultResponse(
    val outcome: String,
    val title: String,
    val turns: Int?,
    val funds: Int?,
    val experience: Int?,
    val loots: List<BattleLootResponse>,
    val quest: String?,
    val enemy: BattleSideResponse,
    val ally: BattleSideResponse,
    val rawLogUrl: String?,
    val rounds: List<BattleRoundResponse> = emptyList(),
    /** UNKNOWN 응답의 진단용 SHA-256이다. API 응답에는 노출하지 않는다. */
    @get:JsonIgnore
    val responseShapeFingerprint: String? = null,
    /** 원문과 식별자를 제외한 UNKNOWN 응답 구조 요약이다. */
    @get:JsonIgnore
    val sanitizedResponseSnippet: String? = null,
) {
    companion object {
        /**
         * 파서가 만든 전투 결과 모델과 회차 목록을 앱 응답 DTO로 변환한다.
         */
        fun from(
            result: HofBattleResult,
            rounds: List<HofBattleResult> = listOf(result),
            responseShapeFingerprint: String? = null,
            sanitizedResponseSnippet: String? = null,
        ): BattleResultResponse =
            BattleResultResponse(
                outcome = result.outcome.name,
                title = result.title,
                turns = result.turns,
                funds = result.funds,
                experience = result.experience,
                loots = result.loots.map(BattleLootResponse::from),
                quest = result.quest,
                enemy = BattleSideResponse.from(result.enemySide),
                ally = BattleSideResponse.from(result.allySide),
                rawLogUrl = result.rawLogUrl,
                rounds = rounds.map(BattleRoundResponse::from),
                responseShapeFingerprint = responseShapeFingerprint,
                sanitizedResponseSnippet = sanitizedResponseSnippet,
            )
    }
}

/**
 * 여러 회차 전투 중 한 회차의 결과를 표현하는 응답 DTO다.
 */
data class BattleRoundResponse(
    val outcome: String,
    val title: String,
    val turns: Int?,
    val funds: Int?,
    val experience: Int?,
    val loots: List<BattleLootResponse>,
    val quest: String?,
    val enemy: BattleSideResponse,
    val ally: BattleSideResponse,
    val rawLogUrl: String?,
) {
    companion object {
        /**
         * 단일 회차 파싱 결과를 회차 응답 DTO로 변환한다.
         */
        fun from(result: HofBattleResult): BattleRoundResponse =
            BattleRoundResponse(
                outcome = result.outcome.name,
                title = result.title,
                turns = result.turns,
                funds = result.funds,
                experience = result.experience,
                loots = result.loots.map(BattleLootResponse::from),
                quest = result.quest,
                enemy = BattleSideResponse.from(result.enemySide),
                ally = BattleSideResponse.from(result.allySide),
                rawLogUrl = result.rawLogUrl,
            )
    }
}

/**
 * 전투 후 획득한 전리품 한 줄을 기존 앱 표시 형식으로 표현하는 응답 DTO다.
 *
 * DB와 파서 내부에서는 이름과 수량을 분리하지만 기존 앱이 `name`을 그대로 표시하므로 원문을 내려준다.
 */
data class BattleLootResponse(
    val name: String,
) {
    companion object {
        /**
         * 파서가 보존한 원문 전리품을 기존 API의 name 필드로 변환한다.
         */
        fun from(loot: HofBattleLoot): BattleLootResponse =
            BattleLootResponse(name = loot.rawText)
    }
}

/**
 * 아군 또는 적군의 HP, 생존자, 총 데미지, 턴 정보를 담는 응답 DTO다.
 */
data class BattleSideResponse(
    val hpCurrent: Int?,
    val hpMax: Int?,
    val survivorsAlive: Int?,
    val survivorsMax: Int?,
    val totalDamage: Int?,
    val turnCurrent: Int?,
    val turnMax: Int?,
) {
    companion object {
        /**
         * 파서가 만든 전투 진영 모델을 API 응답 DTO로 변환한다.
         */
        fun from(side: HofBattleSide): BattleSideResponse =
            BattleSideResponse(
                hpCurrent = side.hpCurrent,
                hpMax = side.hpMax,
                survivorsAlive = side.survivorsAlive,
                survivorsMax = side.survivorsMax,
                totalDamage = side.totalDamage,
                turnCurrent = side.turnCurrent,
                turnMax = side.turnMax,
            )
    }
}
