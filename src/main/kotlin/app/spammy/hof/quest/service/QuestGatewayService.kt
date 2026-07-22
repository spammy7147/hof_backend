package app.spammy.hof.quest.service

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.parser.QuestPageParser
import org.springframework.stereotype.Service

@Service
class QuestGatewayService(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: HofGateway,
    private val loginStateParser: LoginStateParser,
    private val parser: QuestPageParser,
) {
    /** Returns the authenticated quest page as heading-scoped, source-ordered snapshots. */
    fun load(
        accountId: Long,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): List<QuestSnapshot> = execute(accountId, requestFactory.questPage(origin))

    fun accept(
        accountId: Long,
        actionNo: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): List<QuestSnapshot> = execute(accountId, requestFactory.questAction("get", actionNo, origin))

    fun claim(
        accountId: Long,
        actionNo: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): List<QuestSnapshot> = execute(accountId, requestFactory.questAction("complete", actionNo, origin))

    private fun execute(
        accountId: Long,
        request: HofRequest,
    ): List<QuestSnapshot> {
        accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(accountId)
        if (cookies.isEmpty()) throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        val response = gateway.execute(request, cookies)
        val login = loginStateParser.parse(response.body)
        if (login.hasLoginForm && !login.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        return parser.parse(response.body)
    }

}
