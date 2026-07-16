package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.service.AutomationJobService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/automation/jobs")
/**
 * 마이그레이션 전 레거시 job 조회 호환성만 유지하는 종료 API다.
 *
 * 현재 상태 조회 외 모든 변경 endpoint는 410 Gone을 반환하며 신규 실행은 typed 통합 자동화 API가 담당한다.
 */
class AutomationJobController(
    private val automationJobService: AutomationJobService,
) {
    /** 종료된 레거시 job 생성 요청을 410 Gone으로 거부한다. */
    @PostMapping
    fun create(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CreateAutomationJobRequest,
    ): AutomationJobResponse =
        automationJobService.create(accountId = accountId, request = request)

    /**
     * 현재 계정에서 진행 중이거나 대기 중인 job 하나를 조회한다.
     */
    @GetMapping("/current")
    fun findCurrent(
        @CurrentAccountId accountId: Long,
    ): AutomationJobResponse? =
        automationJobService.findCurrent(accountId)

    /**
     * 종료된 레거시 pause 요청을 410 Gone으로 거부한다.
     */
    @PostMapping("/{jobId}/pause")
    fun pause(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): AutomationJobResponse =
        automationJobService.pause(accountId = accountId, jobId = jobId)

    /**
     * 종료된 레거시 resume 요청을 410 Gone으로 거부한다.
     */
    @PostMapping("/{jobId}/resume")
    fun resume(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): AutomationJobResponse =
        automationJobService.resume(accountId = accountId, jobId = jobId)

    /**
     * 종료된 레거시 cancel 요청을 410 Gone으로 거부한다.
     */
    @PostMapping("/{jobId}/cancel")
    fun cancel(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): AutomationJobResponse =
        automationJobService.cancel(accountId = accountId, jobId = jobId)
}
