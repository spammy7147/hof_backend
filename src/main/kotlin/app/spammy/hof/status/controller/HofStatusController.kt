package app.spammy.hof.status.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.status.dto.HofStatusResponse
import app.spammy.hof.status.service.HofStatusService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/status")
/**
 * HOF 홈 화면 상태 조회 API다.
 */
class HofStatusController(
    private val statusService: HofStatusService,
    private val sessionRecoveryService: HofSessionRecoveryService,
) {
    /**
     * HOF 원본 홈 페이지를 호출해 플레이어명, Time, Funds, Work, Auction 상태를 반환한다.
     */
    @GetMapping
    fun fetch(@CurrentAccountId accountId: Long): HofStatusResponse =
        sessionRecoveryService.execute(accountId) {
            statusService.fetch(accountId)
        }
}
