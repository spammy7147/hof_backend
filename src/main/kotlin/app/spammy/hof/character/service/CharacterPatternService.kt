package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.LoadPatternResponse
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.LoginStateParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * HOF 원본 세션에 저장 패턴 슬롯을 로드한다.
 */
class CharacterPatternService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val characterQueryRepository: CharacterQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: HofGateway,
    private val loginStateParser: LoginStateParser,
) {
    private val log = LoggerFactory.getLogger(CharacterPatternService::class.java)

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
        val cookies = cookieQueryRepository.findByAccountId(account.id).toCookieMap()
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
        val response = gateway.execute(requestFactory.loadPattern(hofCharacterId, slot), cookies)
        val loginState = loginStateParser.parse(response.body)
        if (loginState.hasLoginForm && !loginState.isLoggedIn) {
            log.warn(
                "Character pattern load session expired accountId={} characterId={} slot={} status={}",
                account.id,
                hofCharacterId,
                slot,
                response.statusCode,
            )
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }

        val loaded = response.statusCode in 200..399
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
        )
    }

    /**
     * DB 쿠키 Entity 목록을 HOF HTTP client가 쓰는 Map으로 바꾼다.
     */
    private fun List<HofCookieEntity>.toCookieMap(): Map<String, String> =
        associate { cookie -> cookie.name to cookie.value }
}
