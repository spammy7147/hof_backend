package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.CreateAutomationEntryRequest
import app.spammy.hof.automation.dto.ReorderAutomationEntriesRequest
import app.spammy.hof.automation.dto.TypedAutomationAggregateResponse
import app.spammy.hof.automation.dto.UpdateAdventureMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateBattleMapAutomationRequest
import app.spammy.hof.automation.dto.UpdateQuestAutomationRequest
import app.spammy.hof.automation.service.UnifiedAutomationService
import app.spammy.hof.common.security.CurrentAccountId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인증된 계정의 사용자 구성형 통합 자동화 설정과 실행 상태를 노출한다. */
@RestController
@RequestMapping("/api/automation/unified")
class UnifiedAutomationController(
    private val service: UnifiedAutomationService,
) {
    /** 기본 모듈을 합성하지 않고 서버에 저장된 모듈과 현재 실행 상태를 조회한다. */
    @GetMapping
    fun get(@CurrentAccountId accountId: Long): TypedAutomationAggregateResponse = service.getTyped(accountId)

    /** 새 모듈을 현재 우선순위의 마지막에 추가한다. */
    @PostMapping("/entries")
    fun createEntry(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: CreateAutomationEntryRequest,
    ): TypedAutomationAggregateResponse = service.createEntry(accountId, request)

    /** 모듈 유형과 우선순위는 유지하고 사용자 편집 설정만 교체한다. */
    @DeleteMapping("/entries/{entryId}")
    fun deleteEntry(
        @CurrentAccountId accountId: Long,
        @PathVariable entryId: Long,
    ): TypedAutomationAggregateResponse = service.deleteEntry(accountId, entryId)

    /** 소유권을 검증한 모듈을 삭제하고 남은 우선순위를 정규화한다. */
    @PutMapping("/entries/order")
    fun reorder(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: ReorderAutomationEntriesRequest,
    ): TypedAutomationAggregateResponse = service.reorderEntries(accountId, request)

    /** 앱의 드래그 결과를 전체 ID 순서로 받아 서버의 권위 있는 우선순위로 저장한다. */
    @PutMapping("/quest")
    fun updateQuest(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: UpdateQuestAutomationRequest,
    ): TypedAutomationAggregateResponse = service.updateQuest(accountId, request)

    @PutMapping("/battle-maps")
    fun updateBattleMaps(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: UpdateBattleMapAutomationRequest,
    ): TypedAutomationAggregateResponse = service.updateBattleMaps(accountId, request)

    @PutMapping("/adventure-maps")
    fun updateAdventureMaps(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: UpdateAdventureMapAutomationRequest,
    ): TypedAutomationAggregateResponse = service.updateAdventureMaps(accountId, request)

    /** 준비된 활성 모듈이 있는 계정의 통합 자동화를 시작한다. */
    @PostMapping("/start")
    fun start(@CurrentAccountId accountId: Long): TypedAutomationAggregateResponse = service.startTyped(accountId)

    /** 새 행동 선택을 멈추고 현재 자동화를 일시정지한다. */
    @PostMapping("/pause")
    fun pause(@CurrentAccountId accountId: Long): TypedAutomationAggregateResponse = service.pauseTyped(accountId)

    /** 일시정지한 자동화를 최신 모듈 설정으로 다시 평가하도록 요청한다. */
    @PostMapping("/resume")
    fun resume(@CurrentAccountId accountId: Long): TypedAutomationAggregateResponse = service.resumeTyped(accountId)

    /** 활성 통합 자동화 job을 사용자가 명시적으로 종료한다. */
    @PostMapping("/stop")
    fun stop(@CurrentAccountId accountId: Long): TypedAutomationAggregateResponse = service.stopTyped(accountId)
}
