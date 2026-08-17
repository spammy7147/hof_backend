package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.character.dto.LoadPatternResponse
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.external.parser.CharacterDetailParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import app.spammy.hof.character.pattern.CharacterPatternDraft
import app.spammy.hof.character.pattern.CharacterPatternOperationResult
import app.spammy.hof.character.pattern.CharacterPatternOrchestrator
import app.spammy.hof.character.pattern.CharacterPatternRemoteFactory
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.pattern.PatternSlotAfterApply
import app.spammy.hof.character.command.CharacterAutomationGate

@Service
/**
 * HOF 원본 세션에 저장 패턴 슬롯을 로드한다.
 */
class CharacterPatternService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val loginStateParser: LoginStateParser,
    private val detailParser: CharacterDetailParser,
    private val characterService: CharacterService,
    private val snapshotSynchronizer: CharacterSnapshotSynchronizer,
    private val sessionPatternLoadTracker: SessionPatternLoadTracker,
    private val patternRemoteFactory: CharacterPatternRemoteFactory,
    private val automationGate: CharacterAutomationGate,
) {
    private val log = LoggerFactory.getLogger(CharacterPatternService::class.java)
    private val orchestrator = CharacterPatternOrchestrator()

    fun applyDraft(
        accountId: Long,
        characterId: Long,
        base: CharacterPatternSetting,
        baseRevision: java.time.Instant,
        draft: CharacterPatternDraft,
        slotAfterApply: PatternSlotAfterApply = PatternSlotAfterApply.None,
        force: Boolean = false,
    ): CharacterPatternOperationResult = automationGate.execute(
        accountId,
        unavailable = { CharacterPatternOperationResult.RefreshRequired("자동화 일시정지를 기다리고 있습니다.") },
    ) {
        patternRemoteFactory.withRemote(accountId, characterId) { remote ->
            orchestrator.apply(remote, base, baseRevision, draft, slotAfterApply, force)
        }
    }

    fun loadSavedPattern(accountId: Long, characterId: Long, slotCode: String): CharacterPatternOperationResult =
        automationGate.execute(
            accountId,
            unavailable = { CharacterPatternOperationResult.RefreshRequired("자동화 일시정지를 기다리고 있습니다.") },
        ) { patternRemoteFactory.withRemote(accountId, characterId) { remote -> orchestrator.load(remote, slotCode) } }

    fun deleteSavedPattern(accountId: Long, characterId: Long, slotCode: String): CharacterPatternOperationResult =
        automationGate.execute(
            accountId,
            unavailable = { CharacterPatternOperationResult.RefreshRequired("자동화 일시정지를 기다리고 있습니다.") },
        ) { patternRemoteFactory.withRemote(accountId, characterId) { remote -> orchestrator.delete(remote, slotCode) } }

    /**
     * 특정 캐릭터의 저장 패턴 슬롯을 HOF 원본에 로드한다.
     */
    @Transactional(readOnly = true)
    fun loadPattern(
        accountId: Long,
        hofCharacterId: String,
        slot: Int,
    ): LoadPatternResponse {
        if (slot < 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "패턴 번호는 0 이상이어야 합니다.")
        }
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, hofCharacterId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(account.id)
        if (cookies.isEmpty()) {
            log.warn(
                "Character pattern load rejected accountId={} characterId={} slot={} reason=no-cookies",
                account.id,
                hofCharacterId,
                slot,
            )
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        log.info(
            "Character pattern load requested accountId={} characterId={} slot={} cookieNames={}",
            account.id,
            hofCharacterId,
            slot,
            cookies.keys.sorted(),
        )
        val response = sessionPatternLoadTracker.withSession(account.id, cookies) { session ->
            val hofResponse = gateway.execute(account.id, requestFactory.loadPattern(hofCharacterId, slot), cookies)
            val loginState = loginStateParser.parse(hofResponse.body)
            if (loginState.hasLoginForm && !loginState.isLoggedIn) {
                log.warn(
                    "Character pattern load session expired accountId={} characterId={} slot={} status={}",
                    account.id,
                    hofCharacterId,
                    slot,
                    hofResponse.statusCode,
                )
                throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
            }
            if (hofResponse.statusCode in 200..399) {
                session.recordLoaded(BattlePatternLoadRequest(hofCharacterId, slot))
            }
            hofResponse
        }

        val loaded = response.statusCode in 200..399
        val refreshedCharacter = if (loaded) {
            runCatching {
                snapshotSynchronizer.writeParsed(
                    account.id,
                    hofCharacterId,
                    detailParser.parsePage(hofCharacterId, response.body),
                )
            }.onFailure { error ->
                log.warn(
                    "Character pattern load detail sync failed accountId={} characterId={} slot={} error={}",
                    account.id,
                    hofCharacterId,
                    slot,
                    error.message,
                )
            }.getOrNull()
        } else {
            null
        }
        log.info(
            "Character pattern load complete accountId={} characterId={} slot={} status={} loaded={}",
            account.id,
            hofCharacterId,
            slot,
            response.statusCode,
            loaded,
        )

        return LoadPatternResponse(
            accountId = account.id,
            hofCharacterId = hofCharacterId,
            slot = slot,
            loaded = loaded,
            message = if (loaded) "패턴 로드 완료" else "HOF 응답 상태 ${response.statusCode}",
            characterSynchronized = refreshedCharacter != null,
            character = refreshedCharacter,
        )
    }

    /**
     * DB 쿠키 Entity 목록을 HOF HTTP client가 쓰는 Map으로 바꾼다.
     */
}
