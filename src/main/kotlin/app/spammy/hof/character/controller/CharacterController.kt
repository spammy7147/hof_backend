package app.spammy.hof.character.controller

import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.dto.CharacterSyncJobResponse
import app.spammy.hof.character.dto.LoadPatternResponse
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.character.service.CharacterSyncJobService
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
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
) {
    /**
     * 캐릭터 동기화 job을 생성한다. 실제 파싱은 SSE 연결 후 시작된다.
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
    fun findAll(@CurrentAccountId accountId: Long): List<CharacterResponse> =
        characterService.findAll(accountId)

    /**
     * 특정 캐릭터의 상세 스냅샷을 조회한다.
     */
    @GetMapping("/{hofCharacterId}")
    fun findDetail(
        @CurrentAccountId accountId: Long,
        @PathVariable hofCharacterId: String,
    ): CharacterDetailResponse =
        characterService.findDetail(accountId, hofCharacterId)

    /**
     * 특정 캐릭터의 저장 패턴 슬롯을 HOF 원본 세션에 로드한다.
     */
    @PostMapping("/{hofCharacterId}/patterns/{slot}/load")
    fun loadPattern(
        @CurrentAccountId accountId: Long,
        @PathVariable hofCharacterId: String,
        @PathVariable slot: Int,
    ): LoadPatternResponse =
        characterPatternService.loadPattern(
            accountId = accountId,
            hofCharacterId = hofCharacterId,
            slot = slot,
        )
}
