package app.spammy.hof.town.common.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.captcha.service.CaptchaService
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jsoup.Jsoup
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
    private val captchaService: CaptchaService,
) {
    private val actionLocks = ConcurrentHashMap<Long, ReentrantLock>()

    fun load(
        accountId: Long,
        pageUrl: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): ParsedTownPage {
        val context = authenticatedContext(accountId)
        val response = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, origin), context.cookies)
        return formParser.parse(response.body, response.finalUrl)
    }

    /** 원문은 service 경계를 벗어나지 않고 기능 parser에만 전달한다. */
    fun <T> loadProjected(
        accountId: Long,
        pageUrl: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
        projector: (html: String, finalUrl: String, page: ParsedTownPage) -> T,
    ): T {
        val context = authenticatedContext(accountId)
        val response = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, origin), context.cookies)
        val page = formParser.parse(response.body, response.finalUrl)
        return projector(response.body, response.finalUrl, page)
    }

    fun execute(
        accountId: Long,
        pageUrl: String,
        action: TownActionRequest,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): ExecutedTownAction = withAccountActionFence(accountId) {
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, origin), context.cookies)
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val guarded = actionGuard.guard(currentPage, action)
        val actionResponse = executeAuthenticated(
            account = context.account,
            request = requestFactory.townForm(
                method = guarded.form.method,
                actionUrl = guarded.form.actionUrl,
                formEntries = guarded.formEntries,
                origin = origin,
            ),
            cookies = context.cookies + current.setCookies,
        )
        ExecutedTownAction(
            result = resultParser.parse(actionResponse.body),
            page = formParser.parse(actionResponse.body, actionResponse.finalUrl),
        )
    }

    /**
     * 최신 GET에서 의미 action을 고른 뒤 같은 문서의 form을 guard하여 한 번만 제출한다.
     * 기능별 응답 projector에만 HOF HTML을 전달하고 controller DTO에는 포함하지 않는다.
     */
    fun <T> executeProjected(
        accountId: Long,
        pageUrl: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
        resolveAction: (html: String, finalUrl: String, page: ParsedTownPage) -> TownActionRequest,
        projector: (
            html: String,
            finalUrl: String,
            result: app.spammy.hof.town.common.model.ParsedTownResult,
            page: ParsedTownPage,
        ) -> T,
    ): T = withAccountActionFence(accountId) {
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, origin), context.cookies)
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val guarded = actionGuard.guard(currentPage, resolveAction(current.body, current.finalUrl, currentPage))
        val actionResponse = executeAuthenticated(
            account = context.account,
            request = requestFactory.townForm(
                method = guarded.form.method,
                actionUrl = guarded.form.actionUrl,
                formEntries = guarded.formEntries,
                origin = origin,
            ),
            cookies = context.cookies + current.setCookies,
        )
        val result = resultParser.parse(actionResponse.body)
        val page = formParser.parse(actionResponse.body, actionResponse.finalUrl)
        projector(actionResponse.body, actionResponse.finalUrl, result, page)
    }

    /**
     * HOF가 radio가 아닌 가격/번호 입력을 요구하는 기능을 위한 좁은 실행 경계다.
     * 호출자가 compile-time 상수 allowlist를 제공하고, 최신 GET의 같은 form에 실제 존재하는 이름만 덮어쓴다.
     */
    fun <T> executeProjectedWithScalars(
        accountId: Long,
        pageUrl: String,
        action: TownActionRequest,
        scalarValues: Map<String, String>,
        requiredScalarFields: Set<String>,
        requiredSubmitField: String,
        projector: (
            html: String,
            finalUrl: String,
            result: app.spammy.hof.town.common.model.ParsedTownResult,
            page: ParsedTownPage,
        ) -> T,
    ): T = withAccountActionFence(accountId) {
        require(scalarValues.isNotEmpty() && scalarValues.keys == requiredScalarFields)
        require(scalarValues.size <= 8 && scalarValues.values.all { it.length <= 500 })
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE), context.cookies)
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val guarded = actionGuard.guard(currentPage, action)
        if (guarded.form.submitFields.singleOrNull()?.name != requiredSubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 작업 양식이 변경되었습니다.")
        }
        val document = Jsoup.parse(current.body, current.finalUrl)
        val matchingForms = document.select("form").filter { domForm ->
            val names = domForm.select("input,button,select,textarea")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
                .map { it.attr("name") }.toSet()
            val semanticForms = formParser.parse(domForm.outerHtml(), current.finalUrl).forms
            scalarValues.keys.all { it in names } &&
                semanticForms.any { it.actionId == guarded.form.actionId && it.method == guarded.form.method && it.actionUrl == guarded.form.actionUrl }
        }
        if (matchingForms.size != 1) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 입력 양식을 안전하게 확인하지 못했습니다.")
        val controlsByName = matchingForms.single().select("input,select,textarea")
            .filter { !it.hasAttr("disabled") && it.attr("name") in scalarValues.keys }
            .groupBy { it.attr("name") }
        if (scalarValues.keys.any { controlsByName[it].orEmpty().size != 1 }) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 입력 필드가 변경되었습니다.")
        }
        val replacements = scalarValues.toMutableMap()
        val entries = guarded.formEntries.map { field ->
            replacements.remove(field.name)?.let { field.copy(value = it) } ?: field
        }.toMutableList()
        val submitStart = entries.indexOfFirst { field -> guarded.form.submitFields.any { it.name == field.name && it.value == field.value } }
            .let { if (it < 0) entries.size else it }
        replacements.forEach { (name, value) -> entries.add(submitStart, app.spammy.hof.external.model.HofFormField(name, value)) }
        val actionResponse = executeAuthenticated(
            context.account,
            requestFactory.townForm(guarded.form.method, guarded.form.actionUrl, entries, HofRequestOrigin.INTERACTIVE),
            context.cookies + current.setCookies,
        )
        val result = resultParser.parse(actionResponse.body)
        val page = formParser.parse(actionResponse.body, actionResponse.finalUrl)
        projector(actionResponse.body, actionResponse.finalUrl, result, page)
    }

    /** 최신 GET 안에서 actionId까지 결정해 nonce/hidden field 변화와의 TOCTOU를 막는 variant다. */
    fun <T> executeProjectedWithScalars(
        accountId: Long,
        pageUrl: String,
        resolveAction: (html: String, finalUrl: String, page: ParsedTownPage) -> TownActionRequest,
        scalarValues: Map<String, String>,
        requiredScalarFields: Set<String>,
        requiredSubmitField: String,
        projector: (
            html: String,
            finalUrl: String,
            result: app.spammy.hof.town.common.model.ParsedTownResult,
            page: ParsedTownPage,
        ) -> T,
    ): T = withAccountActionFence(accountId) {
        require(scalarValues.isNotEmpty() && scalarValues.keys == requiredScalarFields)
        require(scalarValues.size <= 8 && scalarValues.values.all { it.length <= 500 })
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE), context.cookies)
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val guarded = actionGuard.guard(currentPage, resolveAction(current.body, current.finalUrl, currentPage))
        if (guarded.form.submitFields.singleOrNull()?.name != requiredSubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 작업 양식이 변경되었습니다.")
        }
        val document = Jsoup.parse(current.body, current.finalUrl)
        val matchingForms = document.select("form").filter { domForm ->
            val names = domForm.select("input,button,select,textarea")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
                .map { it.attr("name") }.toSet()
            val semanticForms = formParser.parse(domForm.outerHtml(), current.finalUrl).forms
            scalarValues.keys.all { it in names } && semanticForms.any {
                it.actionId == guarded.form.actionId && it.method == guarded.form.method && it.actionUrl == guarded.form.actionUrl
            }
        }
        if (matchingForms.size != 1) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 입력 양식을 안전하게 확인하지 못했습니다.")
        val controlsByName = matchingForms.single().select("input,select,textarea")
            .filter { !it.hasAttr("disabled") && it.attr("name") in scalarValues.keys }
            .groupBy { it.attr("name") }
        if (scalarValues.keys.any { controlsByName[it].orEmpty().size != 1 }) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 입력 필드가 변경되었습니다.")
        }
        val replacements = scalarValues.toMutableMap()
        val entries = guarded.formEntries.map { field -> replacements.remove(field.name)?.let { field.copy(value = it) } ?: field }.toMutableList()
        val submitStart = entries.indexOfFirst { field -> guarded.form.submitFields.any { it.name == field.name && it.value == field.value } }
            .let { if (it < 0) entries.size else it }
        replacements.forEach { (name, value) -> entries.add(submitStart, app.spammy.hof.external.model.HofFormField(name, value)) }
        val actionResponse = executeAuthenticated(
            context.account,
            requestFactory.townForm(guarded.form.method, guarded.form.actionUrl, entries, HofRequestOrigin.INTERACTIVE),
            context.cookies + current.setCookies,
        )
        val result = resultParser.parse(actionResponse.body)
        val page = formParser.parse(actionResponse.body, actionResponse.finalUrl)
        projector(actionResponse.body, actionResponse.finalUrl, result, page)
    }

    /**
     * 메인 화면의 진입 form과 그 응답에만 존재하는 최종 form을 하나의 계정 fence 안에서 연속 검증한다.
     * 중간 응답을 클라이언트 토큰으로 신뢰하지 않고 매 실행마다 HOF에서 다시 획득한다.
     */
    fun <T> executeTwoStepProjectedWithScalars(
        accountId: Long,
        pageUrl: String,
        entryAction: (ParsedTownPage) -> TownActionRequest,
        finalAction: (ParsedTownPage) -> TownActionRequest,
        scalarValues: Map<String, String>,
        requiredScalarFields: Set<String>,
        requiredFinalSubmitField: String,
        projector: (String, String, app.spammy.hof.town.common.model.ParsedTownResult, ParsedTownPage) -> T,
    ): T = withAccountActionFence(accountId) {
        require(scalarValues.isNotEmpty() && scalarValues.keys == requiredScalarFields)
        require(scalarValues.size <= 8 && scalarValues.values.all { it.length <= 500 })
        val context = authenticatedContext(accountId)
        val main = executeAuthenticated(context.account, requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE), context.cookies)
        val mainPage = formParser.parse(main.body, main.finalUrl)
        val guardedEntry = actionGuard.guard(mainPage, entryAction(mainPage))
        val entryResponse = executeAuthenticated(
            context.account,
            requestFactory.townForm(guardedEntry.form.method, guardedEntry.form.actionUrl, guardedEntry.formEntries, HofRequestOrigin.INTERACTIVE),
            context.cookies + main.setCookies,
        )
        val entryPage = formParser.parse(entryResponse.body, entryResponse.finalUrl)
        val guardedFinal = actionGuard.guard(entryPage, finalAction(entryPage))
        if (guardedFinal.form.submitFields.singleOrNull()?.name != requiredFinalSubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 최종 작업 양식이 변경되었습니다.")
        }
        val document = Jsoup.parse(entryResponse.body, entryResponse.finalUrl)
        val matchingForms = document.select("form").filter { domForm ->
            val names = domForm.select("input,button,select,textarea")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
                .map { it.attr("name") }.toSet()
            val semanticForms = formParser.parse(domForm.outerHtml(), entryResponse.finalUrl).forms
            scalarValues.keys.all { it in names } && semanticForms.any {
                it.actionId == guardedFinal.form.actionId && it.method == guardedFinal.form.method && it.actionUrl == guardedFinal.form.actionUrl
            }
        }
        if (matchingForms.size != 1) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 최종 입력 양식을 안전하게 확인하지 못했습니다.")
        val controlsByName = matchingForms.single().select("input,select,textarea")
            .filter { !it.hasAttr("disabled") && it.attr("name") in scalarValues.keys }
            .groupBy { it.attr("name") }
        if (scalarValues.keys.any { controlsByName[it].orEmpty().size != 1 }) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 최종 입력 필드가 변경되었습니다.")
        }
        controlsByName.forEach { (name, controls) ->
            val control = controls.single()
            if (control.tagName() == "select" && control.select("option").none { it.attr("value") == scalarValues[name] }) {
                throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF에서 허용하지 않는 선택값입니다.")
            }
        }
        val replacements = scalarValues.toMutableMap()
        val entries = guardedFinal.formEntries.map { field -> replacements.remove(field.name)?.let { field.copy(value = it) } ?: field }.toMutableList()
        val submitStart = entries.indexOfFirst { field -> guardedFinal.form.submitFields.any { it.name == field.name && it.value == field.value } }
            .let { if (it < 0) entries.size else it }
        replacements.forEach { (name, value) -> entries.add(submitStart, app.spammy.hof.external.model.HofFormField(name, value)) }
        val finalResponse = executeAuthenticated(
            context.account,
            requestFactory.townForm(guardedFinal.form.method, guardedFinal.form.actionUrl, entries, HofRequestOrigin.INTERACTIVE),
            context.cookies + main.setCookies + entryResponse.setCookies,
        )
        val result = resultParser.parse(finalResponse.body)
        val page = formParser.parse(finalResponse.body, finalResponse.finalUrl)
        projector(finalResponse.body, finalResponse.finalUrl, result, page)
    }

    private fun <T> withAccountActionFence(accountId: Long, action: () -> T): T =
        actionLocks.computeIfAbsent(accountId) { ReentrantLock(true) }.withLock(action)

    private fun authenticatedContext(accountId: Long): AuthenticatedContext {
        val account = accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val cookies = cookieQueryRepository.findValueMapByAccountId(accountId).ifEmpty {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }
        return AuthenticatedContext(account, cookies)
    }

    private fun executeAuthenticated(
        account: HofAccountEntity,
        request: app.spammy.hof.external.model.HofRequest,
        cookies: Map<String, String>,
    ): HofHttpResponse {
        val response = gateway.execute(account.id, request, cookies)
        val login = loginStateParser.parse(response.body)
        if (login.hasLoginForm && !login.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        val sourceUrl = response.finalUrl.ifBlank { request.url }
        if (captchaService.detectAndRecord(account, response.body, sourceUrl) != null) {
            throw ApiException(ErrorCode.CAPTCHA_REQUIRED, "캡차 또는 통행증 입력이 필요합니다.")
        }
        return response
    }

    private data class AuthenticatedContext(
        val account: HofAccountEntity,
        val cookies: Map<String, String>,
    )
}
