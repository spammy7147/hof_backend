package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.AutomationProfileResponse
import app.spammy.hof.automation.dto.CreateAutomationProfileRequest
import app.spammy.hof.automation.dto.UpdateAutomationProfileRequest
import app.spammy.hof.automation.service.AutomationProfileService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/automation/profiles")
/**
 * 홈 화면 자동전투 카드 설정 API다.
 */
class AutomationProfileController(
    private val profileService: AutomationProfileService,
) {
    /**
     * 계정에 저장된 자동전투 카드 목록을 최신 수정 순서로 조회한다.
     */
    @GetMapping
    fun findAll(
        @CurrentAccountId accountId: Long,
    ): List<AutomationProfileResponse> =
        profileService.findAll(accountId)

    /**
     * 새 자동전투 카드를 생성한다.
     */
    @PostMapping
    fun create(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CreateAutomationProfileRequest,
    ): AutomationProfileResponse =
        profileService.create(accountId = accountId, request = request)

    /** 자동전투 카드의 이름, 모드, 구조화된 맵 배열, 활성 여부를 수정한다. */
    @PatchMapping("/{profileId}")
    fun update(
        @CurrentAccountId accountId: Long,
        @PathVariable profileId: Long,
        @RequestBody request: UpdateAutomationProfileRequest,
    ): AutomationProfileResponse =
        profileService.update(accountId = accountId, profileId = profileId, request = request)

    /**
     * 자동전투 카드를 삭제한다.
     */
    @DeleteMapping("/{profileId}")
    fun delete(
        @CurrentAccountId accountId: Long,
        @PathVariable profileId: Long,
    ): ResponseEntity<Void> {
        profileService.delete(accountId = accountId, profileId = profileId)
        return ResponseEntity.noContent().build()
    }
}
