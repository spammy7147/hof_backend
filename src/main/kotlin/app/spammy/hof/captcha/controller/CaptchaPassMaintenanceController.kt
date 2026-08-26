package app.spammy.hof.captcha.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceResponse
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceSettingRequest
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.status.service.HofStatusService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 앱이 계정 전역 통행증 자동 갱신 정책과 관측 상태를 관리하는 API다. */
@RestController
@RequestMapping("/api/captcha/pass-maintenance")
class CaptchaPassMaintenanceController(
    private val maintenance: CaptchaPassMaintenanceService,
    private val statusService: HofStatusService,
    private val sessionRecovery: HofSessionRecoveryService,
) {
    /** 저장된 최신 상태만 조회하며 HOF 원격 요청은 만들지 않는다. */
    @GetMapping
    fun get(@CurrentAccountId accountId: Long): CaptchaPassMaintenanceResponse = maintenance.get(accountId)

    @PutMapping
    fun update(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CaptchaPassMaintenanceSettingRequest,
    ): CaptchaPassMaintenanceResponse = maintenance.setEnabled(accountId, request.enabled)

    /** HOF 홈을 읽기 전용으로 한 번 관측한다. 캡차 challenge 생성이나 제출은 하지 않는다. */
    @PostMapping("/refresh")
    fun refresh(@CurrentAccountId accountId: Long): CaptchaPassMaintenanceResponse {
        sessionRecovery.execute(accountId) { statusService.fetch(accountId) }
        return maintenance.get(accountId)
    }
}
