package app.spammy.hof.status.service

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.HofMainStatusParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.status.dto.HofStatusResponse
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.model.HofRequestOrigin
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * HOF 홈 페이지 상태를 읽어 앱 상단 상태바용 데이터로 변환한다.
 */
class HofStatusService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val loginStateParser: LoginStateParser,
    private val statusParser: HofMainStatusParser,
    private val rosterParser: CharacterRosterParser,
    private val characterQueryRepository: CharacterQueryRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(HofStatusService::class.java)

    /**
     * 저장된 HOF 쿠키로 홈 페이지를 호출하고 플레이어명, Funds, Time, Work, Auction을 파싱한다.
     */
    @Transactional(readOnly = true)
    fun fetch(
        accountId: Long,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofStatusResponse {
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(account.id)
        if (cookies.isEmpty()) {
            log.warn("HOF status rejected accountId={} reason=no-cookies", account.id)
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        log.info("HOF status requested accountId={} cookieNames={}", account.id, cookies.keys.sorted())
        val response = gateway.execute(account.id, requestFactory.home(origin), cookies)
        val loginState = loginStateParser.parse(response.body)
        if (!loginState.isLoggedIn) {
            log.warn("HOF status rejected accountId={} reason=session-expired status={}", account.id, response.statusCode)
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        val parsed = statusParser.parse(response.body)
        val remoteIds = rosterParser.parse(response.body).mapTo(linkedSetOf()) { it.id }
        val localByHofId = characterQueryRepository.findAllByAccountId(account.id).associateBy { it.hofCharacterId }
        val synchronizedCount = remoteIds.count { localByHofId[it]?.detailSyncedAt != null }
        val characterSyncRequired = remoteIds != localByHofId.keys || synchronizedCount != remoteIds.size
        log.info(
            "HOF status parsed accountId={} status={} playerName={} funds={} time={}/{} work={} auction={}",
            account.id,
            response.statusCode,
            parsed.playerName,
            parsed.funds,
            parsed.timeCurrent,
            parsed.timeMax,
            parsed.work,
            parsed.auction,
        )

        return HofStatusResponse(
            accountId = account.id,
            playerName = parsed.playerName,
            funds = parsed.funds,
            timeCurrent = parsed.timeCurrent,
            timeMax = parsed.timeMax,
            work = parsed.work,
            auction = parsed.auction,
            totalCharacterCount = remoteIds.size,
            synchronizedCharacterCount = synchronizedCount,
            characterSyncRequired = characterSyncRequired,
            observedAt = timeProvider.now(),
        )
    }

}
