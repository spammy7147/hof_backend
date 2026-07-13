package app.spammy.hof.battle.controller

import app.spammy.hof.battle.dto.BattleLogResponse
import app.spammy.hof.battle.dto.BattleStatsResponse
import app.spammy.hof.battle.service.BattleLogService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/battle")
/**
 * 저장된 전투 결과 로그와 통계 조회 API다.
 */
class BattleLogController(
    private val battleLogService: BattleLogService,
) {
    /**
     * 최근 전투 로그를 최신순으로 조회한다.
     */
    @GetMapping("/logs")
    fun findRecentLogs(
        @CurrentAccountId accountId: Long,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<BattleLogResponse> =
        battleLogService.findRecent(accountId = accountId, limit = limit)

    /**
     * 누적 전투 횟수, 승률, 보상 합계를 계산해 반환한다.
     */
    @GetMapping("/stats")
    fun summarize(
        @CurrentAccountId accountId: Long,
    ): BattleStatsResponse =
        battleLogService.summarize(accountId)
}
