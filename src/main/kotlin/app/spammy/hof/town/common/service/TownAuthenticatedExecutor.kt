package app.spammy.hof.town.common.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.ExecutedTownAction
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import org.springframework.stereotype.Service

/**
 * 계정 쿠키로 HOF 마을 페이지를 읽고, 실행 직전 다시 파싱한 form만 제출한다.
 *
 * 세션 재인증 재시도는 기존 [app.spammy.hof.account.service.HofSessionRecoveryService]를 사용하는
 * controller 경계가 담당한다. 이 타입은 로그인 form을 감지하면 명시적으로 세션 만료를 보고한다.
 */
@Service
class TownAuthenticatedExecutor(
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val requestFactory: HofRequestFactory,
    private val gateway: AccountHofGateway,
    private val loginStateParser: LoginStateParser,
    private val formParser: HofFormParser,
    private val resultParser: HofResultParser,
    private val actionGuard: TownActionGuard,
) {
    fun load(
        accountId: Long,
        pageUrl: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): ParsedTownPage {
        val cookies = authenticatedCookies(accountId)
        val response = executeAuthenticated(accountId, requestFactory.townPage(pageUrl, origin), cookies)
        return formParser.parse(response.body, response.finalUrl)
    }

    fun execute(
        accountId: Long,
        pageUrl: String,
        action: TownActionRequest,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): ExecutedTownAction {
        val cookies = authenticatedCookies(accountId)
        val current = executeAuthenticated(accountId, requestFactory.townPage(pageUrl, origin), cookies)
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val guarded = actionGuard.guard(currentPage, action)
        val actionResponse = executeAuthenticated(
            accountId = accountId,
            request = requestFactory.townForm(
                method = guarded.form.method,
                actionUrl = guarded.form.actionUrl,
                formFields = guarded.formFields,
                origin = origin,
            ),
            cookies = cookies + current.setCookies,
        )
        return ExecutedTownAction(
            result = resultParser.parse(actionResponse.body),
            page = formParser.parse(actionResponse.body, actionResponse.finalUrl),
        )
    }

    private fun authenticatedCookies(accountId: Long): Map<String, String> {
        accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return cookieQueryRepository.findValueMapByAccountId(accountId).ifEmpty {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }
    }

    private fun executeAuthenticated(
        accountId: Long,
        request: app.spammy.hof.external.model.HofRequest,
        cookies: Map<String, String>,
    ): HofHttpResponse {
        val response = gateway.execute(accountId, request, cookies)
        val login = loginStateParser.parse(response.body)
        if (login.hasLoginForm && !login.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        return response
    }
}
