package app.spammy.hof.battle.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleCategoryId
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofBattleMap
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.parser.BattleMapParser
import app.spammy.hof.external.parser.LoginStateParser
import java.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Precise failure contract consumed by the account-wide automation preflight. */
sealed class AdventureMapRefreshException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Retryable(message: String, cause: Throwable? = null) : AdventureMapRefreshException(message, cause)
    class Fatal(message: String, cause: Throwable? = null) : AdventureMapRefreshException(message, cause)
}

/** Immutable, side-effect-free result of fetching and parsing the authenticated adventure page. */
data class AdventureMapSnapshot(
    val accountId: Long,
    val observations: List<HofBattleMap>,
)

@Service
/**
 * 전투/모험 카테고리의 맵 목록을 조회한다.
 *
 * 맵 이름과 그룹 같은 정적 정보는 DB 카탈로그에 저장하지만, 키 수량과 가능 횟수처럼 계정별로 바뀌는 값이
 * 섞여 있으므로 조회 시마다 HOF 원본 페이지를 호출해 최신 상태로 교체한다. 단, 원본에서 맵을 하나도 파싱하지
 * 못한 비정상 페이지는 기존 가시 상태를 지우지 않고 DB 목록을 그대로 반환한다.
 */
class BattleMapService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val battleMapParser: BattleMapParser,
    private val catalogService: BattleMapCatalogService,
    private val loginStateParser: LoginStateParser,
) {
    private val log = LoggerFactory.getLogger(BattleMapService::class.java)

    /**
     * 계정 세션으로 접근 가능한 맵 목록을 반환한다.
     */
    fun findMaps(
        accountId: Long,
        categoryId: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): List<BattleMapResponse> {
        val snapshot = fetchMapSnapshot(accountId, categoryId, requireObservations = false, origin)
        if (snapshot.observations.isEmpty()) {
            if (snapshot.category == BattleCategoryId.UNION) {
                val pageState = battleMapParser.parseUnionPageState(snapshot.responseBody)
                if (pageState.authoritative) {
                    return synchronizeUnavailableSnapshot(snapshot, pageState.cooldownRemainingSeconds)
                }
            }
            return catalogService.findObservedByCategory(accountId, snapshot.category.value).map(BattleMapResponse::from)
        }
        return synchronizeSnapshot(snapshot)
    }

    /**
     * 이번 HOF 응답에서 실제 관측된 맵만 반환한다. 현재 응답이 비었을 때 DB의 과거 목록으로 폴백하지 않으므로,
     * 레이드처럼 일시적으로만 열리는 전투 CTA의 권한 판정에 사용한다.
     */
    fun findCurrentlyObservedMaps(
        accountId: Long,
        categoryId: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): List<BattleMapResponse> {
        val snapshot = fetchMapSnapshot(accountId, categoryId, requireObservations = false, origin)
        return if (snapshot.observations.isEmpty()) emptyList() else synchronizeSnapshot(snapshot)
    }

    /** 전투 정보실에 노출된 등록 가능 레이드를 자동화 설정에서도 검증할 수 있도록 정적 카탈로그에 반영한다. */
    fun rememberRaidTargets(accountId: Long, targets: Map<String, String>): List<BattleMapResponse> {
        if (targets.isEmpty()) return emptyList()
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return catalogService.synchronizeCategory(
            account,
            BattleCategoryId.RAID.value,
            targets.map { (mapCode, name) ->
                HofBattleMap(
                    categoryId = BattleCategoryId.RAID.value,
                    mapCode = mapCode,
                    name = name,
                    rawHref = "",
                )
            },
        ).map(BattleMapResponse::from)
    }

    /**
     * Performs the authenticated `?sp_hunt` refresh used by the automation daily gate. Unlike the read endpoint,
     * an empty/unparseable page is a failed refresh because no observed account state was synchronized.
     */
    fun refreshAdventureMaps(accountId: Long): List<BattleMapResponse> =
        synchronizeAdventureMapSnapshot(fetchAdventureMapSnapshot(accountId))

    /** Performs only account/cookie reads plus network fetch and parsing; it never writes map state. */
    fun fetchAdventureMapSnapshot(
        accountId: Long,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): AdventureMapSnapshot = try {
        val fetched = fetchMapSnapshot(accountId, BattleCategoryId.ADVENTURE_MAP.value, requireObservations = true, origin)
        AdventureMapSnapshot(accountId = fetched.accountId, observations = fetched.observations.toList())
    } catch (error: Exception) {
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HofAutomationDeferredException>()
            .firstOrNull()
            ?.let { throw it }
        if (error.hasInterruption()) {
            Thread.currentThread().interrupt()
            if (error is AdventureMapRefreshException.Fatal) throw error
            throw AdventureMapRefreshException.Fatal("HOF 모험 맵 요청이 중단되었습니다.", error)
        }
        error.nearestTypedFailure()?.let { throw it }
        if (error.hasTransportFailure()) {
            throw AdventureMapRefreshException.Retryable("HOF 모험 맵 요청 중 네트워크 오류가 발생했습니다.", error)
        }
        throw AdventureMapRefreshException.Fatal("HOF 모험 맵 새로고침에 실패했습니다.", error)
    }

    /** Applies a previously parsed snapshot. Callers may include this in their own token-fenced transaction. */
    fun synchronizeAdventureMapSnapshot(snapshot: AdventureMapSnapshot): List<BattleMapResponse> {
        val fetched = BattleMapSnapshot(
            accountId = snapshot.accountId,
            category = BattleCategoryId.ADVENTURE_MAP,
            observations = snapshot.observations,
        )
        return synchronizeSnapshot(fetched)
    }

    private fun fetchMapSnapshot(
        accountId: Long,
        categoryId: String,
        requireObservations: Boolean,
        origin: HofRequestOrigin,
    ): BattleMapSnapshot {
        val category = BattleCategoryId.fromValue(categoryId)
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "지원하지 않는 전투 카테고리입니다.")
        val source = category.toSource()

        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(account.id)
        if (cookies.isEmpty()) {
            log.warn("Battle map list rejected accountId={} categoryId={} reason=no-cookies", account.id, categoryId)
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        log.info(
            "Battle map list requested accountId={} categoryId={} pageQuery={} mapQuery={} cookieNames={}",
            account.id,
            categoryId,
            source.pageQuery,
            source.mapQuery,
            cookies.keys.sorted(),
        )
        val response = gateway.execute(account.id, requestFactory.battleMapPage(source.pageQuery, origin), cookies)
        val mapPageResponse = source.detailPageQuery
            ?.let { detailPageQuery ->
                gateway.execute(
                    accountId = account.id,
                    request = requestFactory.battleMapPage(detailPageQuery, origin),
                    cookies = cookies + response.setCookies,
                )
            }
            ?: response
        if (requireObservations && mapPageResponse.statusCode !in 200..299) {
            if (mapPageResponse.statusCode in 500..599) {
                throw AdventureMapRefreshException.Retryable(
                    "HOF 모험 맵 서버가 일시적으로 응답하지 않습니다. status=${mapPageResponse.statusCode}",
                )
            }
            throw AdventureMapRefreshException.Fatal(
                "HOF 모험 맵 요청이 거부되었습니다. status=${mapPageResponse.statusCode}",
            )
        }
        val login = loginStateParser.parse(mapPageResponse.body)
        if (login.hasLoginForm && !login.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        val maps = battleMapParser.parse(
            categoryId = category.value,
            queryName = source.mapQuery,
            html = mapPageResponse.body,
        )
        log.info(
            "Battle map list parsed accountId={} categoryId={} status={} detailPageQuery={} mapCount={}",
            account.id,
            categoryId,
            mapPageResponse.statusCode,
            source.detailPageQuery,
            maps.size,
        )

        if (maps.isEmpty()) {
            log.warn(
                "Battle map list preserved accountId={} categoryId={} status={} reason=no-observations",
                account.id,
                category.value,
                mapPageResponse.statusCode,
            )
            if (requireObservations) {
                throw AdventureMapRefreshException.Fatal("HOF 모험 맵 응답에서 상태를 확인하지 못했습니다.")
            }
        }

        return BattleMapSnapshot(account.id, category, maps.toList(), mapPageResponse.body)
    }

    private fun synchronizeSnapshot(snapshot: BattleMapSnapshot): List<BattleMapResponse> {
        val account = accountQueryRepository.findById(snapshot.accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return catalogService.synchronizeCategory(account, snapshot.category.value, snapshot.observations)
            .map(BattleMapResponse::from)
    }

    private fun synchronizeUnavailableSnapshot(
        snapshot: BattleMapSnapshot,
        cooldownRemainingSeconds: Long?,
    ): List<BattleMapResponse> {
        val account = accountQueryRepository.findById(snapshot.accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return catalogService.synchronizeUnavailableCategory(
            account,
            snapshot.category.value,
            cooldownRemainingSeconds,
        ).map(BattleMapResponse::from)
    }

    /**
     * 앱 카테고리 ID를 HOF 원본 페이지 query 값으로 변환한다.
     */
    private fun BattleCategoryId.toSource(): BattleMapSource =
        when (this) {
            BattleCategoryId.BATTLE_MAP -> BattleMapSource(pageQuery = "hunt", mapQuery = "common")
            BattleCategoryId.ADVENTURE_MAP -> BattleMapSource(pageQuery = "sp_hunt", mapQuery = "sp_common")
            BattleCategoryId.UNION -> BattleMapSource(pageQuery = "hunt", mapQuery = "union")
            BattleCategoryId.SCENARIO_OCEAN -> BattleMapSource(
                pageQuery = "raid_hunt",
                mapQuery = "common",
                detailPageQuery = "menu=MapSunkenShip",
            )
            BattleCategoryId.RAID -> BattleMapSource(pageQuery = "raid_hunt", mapQuery = "raid_common")
        }

    private data class BattleMapSource(
        val pageQuery: String,
        val mapQuery: String,
        val detailPageQuery: String? = null,
    )

    private data class BattleMapSnapshot(
        val accountId: Long,
        val category: BattleCategoryId,
        val observations: List<HofBattleMap>,
        val responseBody: String = "",
    )

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun Throwable.hasInterruption(): Boolean = causes().any { it is InterruptedException }

    private fun Throwable.nearestTypedFailure(): AdventureMapRefreshException? =
        causes().filterIsInstance<AdventureMapRefreshException>().firstOrNull()

    private fun Throwable.hasTransportFailure(): Boolean = causes().any { it is IOException }
}
