package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattleResultResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.model.BattleCategoryId
import app.spammy.hof.battle.model.hasUsableKey
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.SessionPatternLoadTracker
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofBattleOutcome
import app.spammy.hof.external.model.HofBattleType
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.BattleResultParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.external.parser.SharedBattleCooldownParser
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 패턴 요청은 실패했지만 전투 요청 호출에는 아직 도달하지 않았다는 증거다. */
class BattleNotSubmittedException(cause: IOException) : RuntimeException(
    "패턴 불러오기 중 통신에 실패했습니다. 전투는 보내지 않았으며 최신 상태에서 다시 판단합니다.",
    cause,
) {
    companion object {
        const val REASON_CODE = "BATTLE_PATTERN_PRELOAD_FAILED"
    }
}

@Service
/**
 * 선택한 맵과 파티로 HOF 원본 전투를 실행한다.
 *
 * 패턴 선로드, 세션 확인, 캡차 감지, 결과 파싱, 전투 로그 저장까지 전투 실행의 전체 흐름을 담당한다.
 */
class BattleRunService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val battleMapService: BattleMapService,
    private val hofStatusSnapshotQueryRepository: HofStatusSnapshotQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val loginStateParser: LoginStateParser,
    private val sharedBattleCooldownParser: SharedBattleCooldownParser,
    private val battleResultParser: BattleResultParser,
    private val battleLogService: BattleLogService,
    private val captchaService: CaptchaService,
    private val sessionPatternLoadTracker: SessionPatternLoadTracker,
    private val mutationFence: AccountHofMutationFence,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(BattleRunService::class.java)

    /**
     * 전투를 실행하고 앱이 표시할 전투 결과 DTO를 반환한다.
     *
     * 캡차/통행증 화면이 감지되면 결과 파싱을 하지 않고 CAPTCHA_REQUIRED 예외를 던져 앱의 전역 모달을 열게 한다.
     */
    fun runBattle(
        accountId: Long,
        request: RunBattleRequest,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): BattleResultResponse = mutationFence.execute(accountId) {
        AccountHofGateway.withCookieChain(accountId) {
            runBattleFenced(accountId, request, origin)
        }
    }

    private fun runBattleFenced(
        accountId: Long,
        request: RunBattleRequest,
        origin: HofRequestOrigin,
    ): BattleResultResponse {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Battle HTTP submission must not run inside a database transaction."
        }
        val category = BattleCategoryId.fromValue(request.categoryId)
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "지원하지 않는 전투 카테고리입니다.")
        val battleType = category.toBattleType()
        val mapCode = request.mapCode.trim()
        if (mapCode.isBlank()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 맵 코드가 비어 있습니다.")
        }
        val battleCount = request.resolvedBattleCount()
        if (battleCount !in setOf(1, 3)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 횟수는 1회 또는 3회만 지원합니다.")
        }

        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val playerName = hofStatusSnapshotQueryRepository.findByAccountId(account.id)?.playerName
        var mapState = battleMapQueryRepository.findStateForExecution(account.id, category.value, mapCode)
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "해결된 계정 전투 맵 상태가 없습니다.")
        // 수동 화면을 열어 둔 동안 다시 출현한 맵을 과거 숨김 상태만으로 차단하지 않는다.
        if (!mapState.visible && origin == HofRequestOrigin.INTERACTIVE) {
            battleMapService.findMaps(account.id, category.value, origin)
            mapState = battleMapQueryRepository.findStateForExecution(account.id, category.value, mapCode)
                ?: throw ApiException(ErrorCode.INVALID_REQUEST, "해결된 계정 전투 맵 상태가 없습니다.")
        }
        validateMapState(mapState, battleCount)
        val cookies = cookieQueryRepository.findValueMapByAccountId(account.id)
        if (cookies.isEmpty()) {
            log.warn("Battle run rejected accountId={} categoryId={} mapCode={} reason=no-cookies", account.id, category.value, mapCode)
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        val characters = resolveCharacters(
            accountId = account.id,
            characterIds = request.characterIds,
        )
        val characterIdSet = characters.map { character -> character.hofCharacterId }.toSet()
        request.patternLoads.forEach { patternLoad ->
            if (patternLoad.characterId !in characterIdSet) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "패턴 로드 캐릭터는 전투 캐릭터에 포함되어야 합니다.")
            }
        }

        log.info(
            "Battle run requested accountId={} categoryId={} mapCode={} battleType={} characters={} battleCount={} cookieNames={}",
            account.id,
            category.value,
            mapCode,
            battleType,
            characters.map { character -> character.hofCharacterId },
            battleCount,
            cookies.keys.sorted(),
        )
        val battleResponse = sessionPatternLoadTracker.withSession(account.id, cookies) { session ->
            session.requiredLoads(request.patternLoads).forEach { patternLoad ->
                log.info(
                    "Battle pattern preload accountId={} categoryId={} mapCode={} characterId={} slot={}",
                    account.id,
                    category.value,
                    mapCode,
                    patternLoad.characterId,
                    patternLoad.slot,
                )
                val preloadResponse = try {
                    gateway.execute(
                        account.id,
                        requestFactory.loadPattern(patternLoad.characterId, patternLoad.slot, origin),
                        cookies,
                    )
                } catch (deferred: HofAutomationDeferredException) {
                    throw HofAutomationDeferredException(
                        retryAt = deferred.retryAt,
                        consecutiveFailures = deferred.consecutiveFailures,
                        requestAttempted = deferred.requestAttempted,
                        actionSubmissionAttempted = false,
                        reasonCode = BATTLE_PATTERN_PRELOAD_DEFERRED,
                    )
                } catch (error: IOException) {
                    throw BattleNotSubmittedException(error)
                }
                ensureActiveSession(
                    response = preloadResponse,
                    message = "HOF 로그인 세션이 만료되어 패턴을 로드하지 못했습니다.",
                )
                if (preloadResponse.statusCode in 200..399) {
                    session.recordLoaded(patternLoad)
                }
            }
            gateway.execute(
                account.id,
                requestFactory.battle(
                    type = battleType,
                    code = mapCode,
                    characterIds = characters.map { character -> character.hofCharacterId },
                    battleCount = battleCount,
                    origin = origin,
                ),
                cookies,
            )
        }
        ensureActiveSession(
            response = battleResponse,
            message = "HOF 로그인 세션이 만료되어 전투를 진행하지 못했습니다.",
        )
        val captchaChallenge = captchaService.detectAndRecord(
            account = account,
            html = battleResponse.body,
            sourceUrl = battleResponse.finalUrl,
        )
        if (captchaChallenge != null) {
            log.warn(
                "Battle run paused by captcha accountId={} categoryId={} mapCode={} captchaId={}",
                account.id,
                category.value,
                mapCode,
                captchaChallenge.id,
            )
            throw ApiException(ErrorCode.CAPTCHA_REQUIRED, "캡차 또는 통행증 입력이 필요합니다.")
        }

        sharedBattleCooldownParser.parse(battleResponse.body)?.let { notice ->
            if (origin == HofRequestOrigin.AUTOMATION) {
                val retryAt = timeProvider.now()
                    .plusSeconds(notice.remainingSeconds + SHARED_COOLDOWN_SCHEDULING_BUFFER_SECONDS)
                throw SharedBattleCooldownRejectedException(retryAt)
            }
            throw ApiException(
                errorCode = ErrorCode.INVALID_REQUEST,
                message = "1분 공유 쿨타임이 남아 있습니다.",
                retryAfterSeconds = notice.remainingSeconds,
            )
        }

        val results = battleResultParser.parseAll(
            html = battleResponse.body,
            playerName = playerName,
            baseUrl = battleResponse.finalUrl,
        )
        val result = results.first()
        val unknownDiagnostics = results
            .takeIf { rounds -> rounds.any { it.outcome == HofBattleOutcome.UNKNOWN } }
            ?.let {
                UnknownBattleResponseDiagnostics(
                    fingerprint = fingerprint(battleResponse.body),
                    snippet = "BattleHttpResponse|status=${battleResponse.statusCode}" +
                        "|bodyLength=${battleResponse.body.length}|rounds=${results.size}" +
                        "|outcomes=${results.joinToString(",") { parsed -> parsed.outcome.name }}",
                )
            }
        unknownDiagnostics?.let { diagnostics ->
            log.warn(
                "Battle response parsed as UNKNOWN accountId={} categoryId={} mapCode={} status={} responseFingerprint={}",
                account.id,
                category.value,
                mapCode,
                battleResponse.statusCode,
                diagnostics.fingerprint,
            )
        }
        log.info(
            "Battle run complete accountId={} categoryId={} mapCode={} status={} rounds={} outcome={} title={}",
            account.id,
            category.value,
            mapCode,
            battleResponse.statusCode,
            results.size,
            result.outcome,
            result.title,
        )
        results.forEach { parsedResult ->
            battleLogService.record(
                account = account,
                request = request.copy(mapCode = mapCode),
                characters = characters,
                result = parsedResult,
            )
        }

        return BattleResultResponse.from(
            result = result,
            rounds = results,
            responseShapeFingerprint = unknownDiagnostics?.fingerprint,
            sanitizedResponseSnippet = unknownDiagnostics?.snippet,
        )
    }

    /**
     * 최근 맵 목록 동기화에서 저장한 계정 상태로 전투 가능 여부를 먼저 검증한다.
     * 이 검증을 통과한 뒤의 HOF 패턴 로드와 전투 요청 의미는 기존과 동일하다.
     */
    private fun validateMapState(state: AccountBattleMapStateEntity, battleCount: Int) {
        if (!state.visible) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 계정에서 보이지 않는 전투 맵입니다.")
        }
        if (!state.battleMap.enabled) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "비활성화된 전투 맵입니다.")
        }
        if (battleCount == 3 && !state.supportsThreeBattles) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "이 계정의 현재 맵 페이지에서 3회 전투 기능을 확인하지 못했습니다.")
        }
        if (state.cooldownUntil?.isAfter(timeProvider.now()) == true) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 맵 쿨타임이 남아 있습니다.")
        }
        if (!state.keyMode.hasUsableKey(state.keyCount)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 맵에 필요한 키가 없습니다.")
        }
        if (state.availableCount != null && state.availableCount!! <= 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 맵의 남은 가능 횟수가 없습니다.")
        }
        if (state.attemptRemaining != null && state.attemptRemaining!! <= 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 맵의 남은 도전 횟수가 없습니다.")
        }
        if (state.winRemaining != null && state.winRemaining!! <= 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 맵의 남은 승리 횟수가 없습니다.")
        }
    }

    /**
     * 요청에 포함된 캐릭터 ID 목록을 DB 캐릭터 Entity 목록으로 변환한다.
     *
     * 중복 ID는 제거하고, 1~5명 범위를 벗어나면 잘못된 요청으로 처리한다.
     */
    private fun resolveCharacters(
        accountId: Long,
        characterIds: List<String>,
    ): List<CharacterEntity> {
        val normalizedIds = characterIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedIds.isEmpty()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투에 사용할 캐릭터를 선택해야 합니다.")
        }
        if (normalizedIds.size > 5) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "전투 파티는 최대 5명까지 선택할 수 있습니다.")
        }

        val charactersByHofId = characterQueryRepository
            .findByAccountIdAndHofCharacterIds(accountId, normalizedIds)
            .associateBy { it.hofCharacterId }

        return normalizedIds.map { characterId ->
            charactersByHofId[characterId]
                ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캐릭터를 찾지 못했습니다. characterId=$characterId")
        }
    }

    /**
     * HOF 응답이 로그인 화면으로 돌아간 상태인지 확인한다.
     */
    private fun ensureActiveSession(
        response: HofHttpResponse,
        message: String,
    ) {
        val loginState = loginStateParser.parse(response.body)
        if (!loginState.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, message)
        }
    }

    private fun fingerprint(body: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(StandardCharsets.UTF_8)),
    )

    private data class UnknownBattleResponseDiagnostics(
        val fingerprint: String,
        val snippet: String,
    )

    /**
     * 앱 카테고리 ID를 HOF 전투 요청 타입으로 변환한다.
     */
    private fun BattleCategoryId.toBattleType(): HofBattleType =
        when (this) {
            BattleCategoryId.BATTLE_MAP -> HofBattleType.COMMON
            BattleCategoryId.ADVENTURE_MAP -> HofBattleType.ADVENTURE
            BattleCategoryId.UNION -> HofBattleType.UNION
            BattleCategoryId.SCENARIO_OCEAN -> HofBattleType.COMMON
            BattleCategoryId.RAID -> HofBattleType.RAID
        }

    private companion object {
        const val BATTLE_PATTERN_PRELOAD_DEFERRED = "BATTLE_PATTERN_PRELOAD_DEFERRED"
        const val SHARED_COOLDOWN_SCHEDULING_BUFFER_SECONDS = 2L
    }
}

class SharedBattleCooldownRejectedException(
    val retryAt: Instant,
) : RuntimeException("Shared battle cooldown is active until $retryAt")
