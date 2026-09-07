package app.spammy.hof.character.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.dto.CharacterSyncJobResponse
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.character.service.CharacterSyncJobService
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.character.service.CharacterOperationJobService
import app.spammy.hof.character.dto.CharacterOperationJobResponse
import app.spammy.hof.character.dto.CharacterCurrentOperationResponse
import app.spammy.hof.character.dto.CharacterRecoveryPreviewResponse
import app.spammy.hof.character.dto.CharacterRecoveryAcceptRequest
import app.spammy.hof.character.service.CharacterOperationRecoveryService
import app.spammy.hof.character.command.CharacterCommand
import app.spammy.hof.character.command.CharacterCommandExecutor
import app.spammy.hof.character.command.CharacterCommandResult
import app.spammy.hof.character.command.CharacterCommandPreview
import app.spammy.hof.character.dto.CharacterPatternApplyRequest
import app.spammy.hof.character.dto.CharacterPatternSlotCommandRequest
import app.spammy.hof.character.pattern.CharacterPatternOperationResult
import app.spammy.hof.character.dto.CharacterManualLinkRequest
import app.spammy.hof.character.dto.CharacterLifecycleRequest
import app.spammy.hof.character.dto.CharacterTransferPreviewRequest
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.transfer.CharacterTransferSelection
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.character.transfer.CharacterTransferPreview
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/characters")
/**
 * 캐릭터 목록, 상세, SSE 동기화, 저장 패턴 로드 API다.
 */
class CharacterController(
    private val characterService: CharacterService,
    private val characterSyncJobService: CharacterSyncJobService,
    private val characterPatternService: CharacterPatternService,
    private val characterSnapshotSynchronizer: CharacterSnapshotSynchronizer,
    private val characterOperationJobService: CharacterOperationJobService,
    private val characterCommandExecutor: CharacterCommandExecutor,
    private val characterLifecycleService: CharacterLifecycleService,
    private val characterTransferService: CharacterTransferService,
    private val sessionRecoveryService: HofSessionRecoveryService,
    private val characterRecoveryService: CharacterOperationRecoveryService,
) {
    /**
     * 캐릭터 동기화 job을 생성하고 즉시 백그라운드 실행한다.
     */
    @PostMapping("/sync-jobs")
    fun startSyncJob(@CurrentAccountId accountId: Long): CharacterSyncJobResponse =
        characterSyncJobService.startSyncJob(accountId)

    /**
     * 캐릭터 동기화 job의 현재 snapshot을 조회한다.
     */
    @GetMapping("/sync-jobs/{jobId}")
    fun findSyncJob(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): CharacterSyncJobResponse =
        characterSyncJobService.findSyncJob(accountId, jobId)

    @PostMapping("/sync-jobs/{jobId}/stop")
    fun stopSyncJob(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): CharacterSyncJobResponse = characterSyncJobService.stopSyncJob(accountId, jobId)

    @PostMapping("/sync-jobs/{jobId}/resume")
    fun resumeSyncJob(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): CharacterSyncJobResponse = characterSyncJobService.resumeSyncJob(accountId, jobId)

    /**
     * 캐릭터 동기화 진행 이벤트를 SSE로 스트리밍한다.
     */
    @GetMapping("/sync-jobs/{jobId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamSyncJobEvents(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): SseEmitter =
        characterSyncJobService.streamSyncJobEvents(accountId, jobId)

    /**
     * DB에 저장된 캐릭터 목록을 조회한다.
     */
    @GetMapping
    fun findAll(
        @CurrentAccountId accountId: Long,
        @RequestParam(required = false) lifecycle: CharacterLifecycle?,
    ): List<CharacterResponse> = characterService.findAll(accountId, lifecycle)

    /** 앱 내 이동은 Knockback에도 변하지 않는 안정 캐릭터 기록 ID를 사용한다. */
    @GetMapping("/records/{characterId}")
    fun findDetailById(
        @CurrentAccountId accountId: Long,
        @PathVariable characterId: Long,
    ): CharacterDetailResponse = characterService.findDetailById(accountId, characterId)

    @PostMapping("/records/{characterId}/refresh")
    fun refreshDetailById(
        @CurrentAccountId accountId: Long,
        @PathVariable characterId: Long,
    ): CharacterDetailResponse = sessionRecoveryService.execute(accountId) {
        characterSnapshotSynchronizer.refresh(accountId, characterId)
    }

    @PostMapping("/records/{characterId}/deep-sync-jobs")
    fun startDeepSyncJob(
        @CurrentAccountId accountId: Long,
        @PathVariable characterId: Long,
    ): CharacterOperationJobResponse = characterOperationJobService.startDeepSync(accountId, characterId)

    @GetMapping("/operation-jobs/{jobId}")
    fun findOperationJob(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): CharacterOperationJobResponse = characterOperationJobService.find(accountId, jobId)

    @GetMapping("/operation-jobs/current")
    fun findCurrentOperationJob(
        @CurrentAccountId accountId: Long,
        @RequestParam(required = false) characterId: Long?,
    ): CharacterCurrentOperationResponse = CharacterCurrentOperationResponse(characterOperationJobService.findCurrent(accountId, characterId))

    @PostMapping("/operation-jobs/{jobId}/retry-recovery")
    fun retryRecovery(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): CharacterOperationJobResponse = characterOperationJobService.retryRecovery(accountId, jobId)

    @PostMapping("/operation-jobs/{jobId}/recovery-preview")
    fun previewRecovery(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
    ): CharacterRecoveryPreviewResponse = characterRecoveryService.preview(accountId, jobId)

    @PostMapping("/operation-jobs/{jobId}/accept-current")
    fun acceptCurrentRecovery(
        @CurrentAccountId accountId: Long,
        @PathVariable jobId: Long,
        @RequestBody request: CharacterRecoveryAcceptRequest,
    ): CharacterOperationJobResponse = characterRecoveryService.accept(accountId, jobId, request.confirmationToken)

    @PostMapping("/commands")
    fun executeCommand(
        @CurrentAccountId accountId: Long,
        @RequestBody command: CharacterCommand,
    ): CharacterCommandResult = sessionRecoveryService.execute(accountId) {
        characterCommandExecutor.execute(accountId, command)
    }

    @PostMapping("/commands/preview")
    fun previewCommand(
        @CurrentAccountId accountId: Long,
        @RequestBody command: CharacterCommand,
    ): CharacterCommandPreview = characterCommandExecutor.preview(accountId, command)

    @PostMapping("/patterns/apply")
    fun applyPattern(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterPatternApplyRequest,
    ): CharacterPatternOperationResult = sessionRecoveryService.execute(accountId) {
        characterPatternService.applyDraft(
            accountId,
            request.characterId,
            request.base,
            request.baseRevision,
            request.draft,
            request.slotAfterApply(),
            request.force,
        )
    }

    @PostMapping("/patterns/load")
    fun loadSavedPattern(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterPatternSlotCommandRequest,
    ): CharacterPatternOperationResult = sessionRecoveryService.execute(accountId) {
        characterPatternService.loadSavedPattern(accountId, request.characterId, request.slotCode)
    }

    @PostMapping("/patterns/delete")
    fun deleteSavedPattern(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterPatternSlotCommandRequest,
    ): CharacterPatternOperationResult = sessionRecoveryService.execute(accountId) {
        characterPatternService.deleteSavedPattern(accountId, request.characterId, request.slotCode)
    }

    @PostMapping("/identity/link")
    fun linkCharacter(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterManualLinkRequest,
    ): List<CharacterResponse> {
        characterLifecycleService.link(
            accountId,
            request.characterId,
            request.newHofCharacterId,
            CharacterHofIdLinkReason.MANUAL_LINK,
            userConfirmed = true,
        )
        return characterService.findAll(accountId)
    }

    @PostMapping("/archive")
    fun archiveCharacter(@CurrentAccountId accountId: Long, @RequestBody request: CharacterLifecycleRequest): List<CharacterResponse> {
        characterLifecycleService.archive(accountId, request.characterId)
        return characterService.findAll(accountId)
    }

    @PostMapping("/restore-jobs")
    fun startRestoreCharacter(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterLifecycleRequest,
    ): CharacterOperationJobResponse = characterOperationJobService.startRestore(accountId, request.characterId)

    @PostMapping("/delete-permanently")
    fun deleteCharacterPermanently(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterLifecycleRequest,
    ): List<CharacterResponse> {
        characterLifecycleService.deletePermanently(accountId, request.characterId)
        return characterService.findAll(accountId)
    }

    @PostMapping("/transfers/preview")
    fun previewTransfer(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterTransferPreviewRequest,
    ): CharacterTransferPreview = characterTransferService.preview(
        accountId,
        CharacterTransferSelection(request.sourceCharacterId, request.targetCharacterId, request.transfer),
    )

    @PostMapping("/transfers/jobs")
    fun startTransferJob(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CharacterTransferExecuteRequest,
    ): CharacterOperationJobResponse = characterOperationJobService.startTransfer(accountId, request)
}
