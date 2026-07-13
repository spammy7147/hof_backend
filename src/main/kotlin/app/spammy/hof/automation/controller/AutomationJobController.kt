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
 * 자동화 job 실행 상태를 다루는 API다.
 *
 * 현재는 자동전투 실행 루프 전 단계의 골격이며, job 생성/조회/상태 변경을 담당한다.
 */
class AutomationJobController(
    private val automationJobService: AutomationJobService,
) {
    /** 계정 소유 프로필 ID로 자동화 job row를 생성한다. */
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
     * 실행 중인 자동화 job을 일시정지 상태로 바꾼다.
     */
    @PostMapping("/{jobId}/pause")
    fun pause(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): AutomationJobResponse =
        automationJobService.pause(accountId = accountId, jobId = jobId)

    /**
     * 일시정지된 자동화 job을 재개 대기 상태로 바꾼다.
     */
    @PostMapping("/{jobId}/resume")
    fun resume(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): AutomationJobResponse =
        automationJobService.resume(accountId = accountId, jobId = jobId)

    /**
     * 자동화 job을 취소 상태로 바꾼다.
     */
    @PostMapping("/{jobId}/cancel")
    fun cancel(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): AutomationJobResponse =
        automationJobService.cancel(accountId = accountId, jobId = jobId)
}
