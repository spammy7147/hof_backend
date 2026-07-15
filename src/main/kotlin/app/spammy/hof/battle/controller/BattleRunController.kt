package app.spammy.hof.battle.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.battle.dto.BattleResultResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.common.security.CurrentAccountId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/battles")
/**
 * 실제 전투 실행 API다.
 */
class BattleRunController(
    private val battleRunService: BattleRunService,
    private val sessionRecoveryService: HofSessionRecoveryService,
) {
    /**
     * 선택한 맵과 파티/패턴 정보로 HOF 원본 서버에 전투를 요청한다.
     */
    @PostMapping("/run")
    fun runBattle(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: RunBattleRequest,
    ): BattleResultResponse =
        sessionRecoveryService.execute(accountId) {
            battleRunService.runBattle(accountId = accountId, request = request)
        }
}
