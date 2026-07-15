package app.spammy.hof.battle.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleCategoryId
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofBattleMap
import app.spammy.hof.external.parser.BattleMapParser
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
    private val gateway: HofGateway,
    private val battleMapParser: BattleMapParser,
    private val catalogService: BattleMapCatalogService,
) {
    private val log = LoggerFactory.getLogger(BattleMapService::class.java)

    /**
     * 계정 세션으로 접근 가능한 맵 목록을 반환한다.
     */
    fun findMaps(
        accountId: Long,
        categoryId: String,
    ): List<BattleMapResponse> {
        val snapshot = fetchMapSnapshot(accountId, categoryId, requireObservations = false)
        if (snapshot.observations.isEmpty()) {
            return catalogService.findVisibleByCategory(accountId, snapshot.category.value).map(BattleMapResponse::from)
        }
        return synchronizeSnapshot(snapshot)
    }

    /**
     * Performs the authenticated `?sp_hunt` refresh used by the automation daily gate. Unlike the read endpoint,
     * an empty/unparseable page is a failed refresh because no observed account state was synchronized.
     */
    fun refreshAdventureMaps(accountId: Long): List<BattleMapResponse> =
        synchronizeAdventureMapSnapshot(fetchAdventureMapSnapshot(accountId))

    /** Performs only account/cookie reads plus network fetch and parsing; it never writes map state. */
    fun fetchAdventureMapSnapshot(accountId: Long): AdventureMapSnapshot = try {
        val fetched = fetchMapSnapshot(accountId, BattleCategoryId.ADVENTURE_MAP.value, requireObservations = true)
        AdventureMapSnapshot(accountId = fetched.accountId, observations = fetched.observations.toList())
    } catch (error: Exception) {
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
        val response = gateway.execute(requestFactory.battleMapPage(source.pageQuery), cookies)
        val mapPageResponse = source.detailPageQuery
            ?.let { detailPageQuery ->
                gateway.execute(
                    request = requestFactory.battleMapPage(detailPageQuery),
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

        return BattleMapSnapshot(account.id, category, maps.toList())
    }

    private fun synchronizeSnapshot(snapshot: BattleMapSnapshot): List<BattleMapResponse> {
        val account = accountQueryRepository.findById(snapshot.accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return catalogService.synchronizeCategory(account, snapshot.category.value, snapshot.observations)
            .map(BattleMapResponse::from)
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
    )

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun Throwable.hasInterruption(): Boolean = causes().any { it is InterruptedException }

    private fun Throwable.nearestTypedFailure(): AdventureMapRefreshException? =
        causes().filterIsInstance<AdventureMapRefreshException>().firstOrNull()

    private fun Throwable.hasTransportFailure(): Boolean = causes().any { it is IOException }
}
