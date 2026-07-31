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
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.ExecutedTownAction
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownSelectionType
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

sealed interface TownObservedAction {
    data class Form(val request: TownActionRequest) : TownObservedAction
    data class Link(val query: List<HofFormField>) : TownObservedAction
}

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

    /**
     * 최신 GET에 실제 존재하는 링크를 기능 parser가 opaque query로 해석한 뒤 같은 계정 fence 안에서 한 번 실행한다.
     * 임의 URL은 받지 않고 호출자가 선언한 query 이름 집합과 정확히 일치할 때만 HOF same-origin GET을 허용한다.
     */
    fun <T> executeObservedGetProjected(
        accountId: Long,
        pageUrl: String,
        requiredQueryFields: Set<String>,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
        resolveQuery: (html: String, finalUrl: String, page: ParsedTownPage) -> List<HofFormField>,
        projector: (html: String, finalUrl: String, result: app.spammy.hof.town.common.model.ParsedTownResult, page: ParsedTownPage) -> T,
    ): T = withAccountActionFence(accountId) {
        require(requiredQueryFields.isNotEmpty() && requiredQueryFields.size <= 8)
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, origin),
            context.cookies,
        )
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val query = resolveQuery(current.body, current.finalUrl, currentPage)
        if (query.size != requiredQueryFields.size || query.map(HofFormField::name).toSet() != requiredQueryFields ||
            query.any { it.name.length > 80 || it.value.isBlank() || it.value.length > 500 }
        ) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF action을 안전하게 확인하지 못했습니다.")
        val actionResponse = executeAuthenticated(
            context.account,
            requestFactory.townObservedGet(pageUrl, query, origin),
            context.cookies + current.setCookies,
        )
        val result = resultParser.parse(actionResponse.body)
        val page = formParser.parse(actionResponse.body, actionResponse.finalUrl)
        projector(actionResponse.body, actionResponse.finalUrl, result, page)
    }

    /**
     * 상위 목록에서 상세 링크를, 상세에서 최종 form/링크를 매번 다시 관측한 뒤 한 계정 fence 안에서 실행한다.
     * 클라이언트가 목록 또는 상세 URL/query/form 값을 공급하지 않으므로 오래된 화면이 새 대상을 잘못 실행하지 않는다.
     */
    fun <T> executeNestedObservedActionProjected(
        accountId: Long,
        rootPageUrl: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
        resolveDetailUrl: (html: String, finalUrl: String, page: ParsedTownPage) -> String,
        resolveAction: (html: String, finalUrl: String, page: ParsedTownPage) -> TownObservedAction,
        projector: (
            html: String,
            finalUrl: String,
            result: app.spammy.hof.town.common.model.ParsedTownResult,
            page: ParsedTownPage,
        ) -> T,
    ): T = withAccountActionFence(accountId) {
        val context = authenticatedContext(accountId)
        val root = executeAuthenticated(
            context.account,
            requestFactory.townPage(rootPageUrl, origin),
            context.cookies,
        )
        val rootPage = formParser.parse(root.body, root.finalUrl)
        val detailUrl = resolveDetailUrl(root.body, root.finalUrl, rootPage)
        val detail = executeAuthenticated(
            context.account,
            requestFactory.townPage(detailUrl, origin),
            context.cookies + root.setCookies,
        )
        val detailPage = formParser.parse(detail.body, detail.finalUrl)
        val observed = resolveAction(detail.body, detail.finalUrl, detailPage)
        val actionResponse = when (observed) {
            is TownObservedAction.Form -> {
                val guarded = actionGuard.guard(detailPage, observed.request)
                executeAuthenticated(
                    context.account,
                    requestFactory.townForm(guarded.form.method, guarded.form.actionUrl, guarded.formEntries, origin),
                    context.cookies + root.setCookies + detail.setCookies,
                )
            }
            is TownObservedAction.Link -> {
                val query = observed.query
                if (query.isEmpty() || query.size > 8 || query.map(HofFormField::name).distinct().size != query.size ||
                    query.any { it.name.isBlank() || it.name.length > 80 || it.value.isBlank() || it.value.length > 500 }
                ) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF action 링크를 안전하게 확인하지 못했습니다.")
                executeAuthenticated(
                    context.account,
                    requestFactory.townObservedGet(detail.finalUrl, query, origin),
                    context.cookies + root.setCookies + detail.setCookies,
                )
            }
        }
        val result = resultParser.parse(actionResponse.body)
        val page = formParser.parse(actionResponse.body, actionResponse.finalUrl)
        projector(actionResponse.body, actionResponse.finalUrl, result, page)
    }

    /**
     * 페이지의 select 변경만 HOF에 전달하고 그 응답을 읽는 비파괴 옵션 전환 경계다.
     *
     * 브라우저의 onchange 구현이나 임의 query를 재현하지 않는다. 최신 GET에서 관측한 동일 form의
     * opaque option만 허용하며, 실제 작업을 실행하는 submit/hidden action 필드는 전송하지 않는다.
     */
    fun <T> loadSelectedOptionProjected(
        accountId: Long,
        pageUrl: String,
        actionId: (String, String, ParsedTownPage) -> String,
        optionCandidateId: String,
        requiredOptionField: String,
        requiredFormSubmitField: String,
        excludedActionFields: Set<String>,
        projector: (html: String, finalUrl: String, page: ParsedTownPage) -> T,
    ): T = withAccountActionFence(accountId) {
        require(requiredOptionField.isNotBlank() && requiredFormSubmitField.isNotBlank())
        require(excludedActionFields.isNotEmpty() && requiredFormSubmitField in excludedActionFields)
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE),
            context.cookies,
        )
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val formId = actionId(current.body, current.finalUrl, currentPage)
        val guarded = actionGuard.guard(
            currentPage,
            TownActionRequest(formId, listOf(TownActionSelection(optionCandidateId))),
        )
        if (guarded.form.submitFields.singleOrNull()?.name != requiredFormSubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 분류 양식이 변경되었습니다.")
        }
        val option = guarded.form.candidates.singleOrNull { it.id == optionCandidateId }
            ?.takeIf { it.inputName == requiredOptionField && it.selectionType == TownSelectionType.SELECT }
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF에서 선택할 수 없는 분류입니다.")
        // hidden Create/refine 같은 관측된 action trigger도 기능별 denylist에 따라 전송하지 않는다.
        val entries = guarded.form.hiddenFields.filterNot { it.name in excludedActionFields }.toMutableList().apply {
            add(app.spammy.hof.external.model.HofFormField(option.inputName, option.inputValue))
        }
        val transitioned = executeAuthenticated(
            context.account,
            requestFactory.townForm(guarded.form.method, guarded.form.actionUrl, entries, HofRequestOrigin.INTERACTIVE),
            context.cookies + current.setCookies,
        )
        val transitionedPage = formParser.parse(transitioned.body, transitioned.finalUrl)
        projector(transitioned.body, transitioned.finalUrl, transitionedPage)
    }

    /**
     * 기능 parser가 최신 GET의 유일한 category select 이름까지 관측해야 하는 form을 위한 variant다.
     * field 이름을 API 입력이나 고정 추측으로 받지 않고, 같은 account fence 안의 opaque candidate에서 재확인한다.
     */
    fun <T> loadResolvedSelectedOptionProjected(
        accountId: Long,
        pageUrl: String,
        optionCandidateId: String,
        excludedActionFields: Set<String>,
        resolveContract: (html: String, finalUrl: String, page: ParsedTownPage) -> Triple<String, String, String>,
        projector: (html: String, finalUrl: String, page: ParsedTownPage) -> T,
    ): T = withAccountActionFence(accountId) {
        require(excludedActionFields.isNotEmpty())
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE),
            context.cookies,
        )
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val (actionId, optionField, submitField) = resolveContract(current.body, current.finalUrl, currentPage)
        if (optionField.isBlank() || submitField.isBlank() || optionField.length > 80 || submitField.length > 80) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 분류 양식을 안전하게 확인하지 못했습니다.")
        }
        val guarded = actionGuard.guard(
            currentPage,
            TownActionRequest(actionId, listOf(TownActionSelection(optionCandidateId))),
        )
        if (guarded.form.submitFields.singleOrNull()?.name != submitField || submitField !in excludedActionFields) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 분류 양식이 변경되었습니다.")
        }
        val option = guarded.form.candidates.singleOrNull { it.id == optionCandidateId }
            ?.takeIf { it.inputName == optionField && it.selectionType == TownSelectionType.SELECT }
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF에서 선택할 수 없는 분류입니다.")
        val categoryFields = guarded.form.candidates.filter { it.selectionType == TownSelectionType.SELECT }
            .map { it.inputName }.distinct()
        if (categoryFields != listOf(optionField)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 분류 선택란을 하나로 확인하지 못했습니다.")
        }
        val entries = guarded.form.hiddenFields.filterNot { it.name in excludedActionFields }.toMutableList().apply {
            add(HofFormField(option.inputName, option.inputValue))
        }
        val transitioned = executeAuthenticated(
            context.account,
            requestFactory.townForm(guarded.form.method, guarded.form.actionUrl, entries, HofRequestOrigin.INTERACTIVE),
            context.cookies + current.setCookies,
        )
        val transitionedPage = formParser.parse(transitioned.body, transitioned.finalUrl)
        projector(transitioned.body, transitioned.finalUrl, transitionedPage)
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
     * action 응답이 원래 화면을 포함하지 않는 HOF 기능을 위해, 같은 계정 fence 안에서 최신 GET을 최대 한 번 보충한다.
     * action 결과는 보충 GET으로 덮지 않고 최종 projector에 그대로 전달한다.
     */
    fun <T> executeProjectedWithSingleFallbackGet(
        accountId: Long,
        pageUrl: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
        resolveAction: (html: String, finalUrl: String, page: ParsedTownPage) -> TownActionRequest,
        acceptsActionResponse: (T) -> Boolean,
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
        val actionPage = formParser.parse(actionResponse.body, actionResponse.finalUrl)
        val projected = projector(actionResponse.body, actionResponse.finalUrl, result, actionPage)
        if (acceptsActionResponse(projected)) return@withAccountActionFence projected

        val refreshed = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, origin),
            context.cookies + current.setCookies + actionResponse.setCookies,
        )
        val refreshedPage = formParser.parse(refreshed.body, refreshed.finalUrl)
        projector(refreshed.body, refreshed.finalUrl, result, refreshedPage)
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

    /**
     * opaque candidate에서 파생되는 scalar도 최신 GET 안에서만 해석하는 variant다.
     * onclick 등 페이지 관측값을 사전 GET에서 가져와 재사용하지 않도록 action과 scalar를 함께 결정한다.
     */
    fun <T> executeResolvedProjectedWithScalars(
        accountId: Long,
        pageUrl: String,
        requiredScalarFields: Set<String>,
        requiredSubmitField: String,
        resolve: (html: String, finalUrl: String, page: ParsedTownPage) -> Pair<TownActionRequest, Map<String, String>>,
        projector: (
            html: String,
            finalUrl: String,
            result: app.spammy.hof.town.common.model.ParsedTownResult,
            page: ParsedTownPage,
        ) -> T,
    ): T = withAccountActionFence(accountId) {
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE),
            context.cookies,
        )
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val (action, scalarValues) = resolve(current.body, current.finalUrl, currentPage)
        if (scalarValues.isEmpty() || scalarValues.keys != requiredScalarFields || scalarValues.size > 8 ||
            scalarValues.values.any { it.length > 500 }
        ) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 입력값을 안전하게 확인하지 못했습니다.")
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

    /**
     * 인재 모집처럼 이름 input의 서버 필드명이 페이지마다 바뀔 수 있는 단일 문자열 form 경계다.
     * 최신 GET에서 모집 form, 의미가 관측된 name input 하나와 opaque 선택지를 함께 다시 결정한다.
     */
    fun <T> executeRecruitmentProjected(
        accountId: Long,
        pageUrl: String,
        resolve: (String, String, ParsedTownPage) -> Triple<TownActionRequest, HofFormField, Int>,
        projector: (String, String, app.spammy.hof.town.common.model.ParsedTownResult, ParsedTownPage) -> T,
    ): T = withAccountActionFence(accountId) {
        val context = authenticatedContext(accountId)
        val current = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE),
            context.cookies,
        )
        val currentPage = formParser.parse(current.body, current.finalUrl)
        val (action, nameField, maximumLength) = resolve(current.body, current.finalUrl, currentPage)
        if (nameField.name.isBlank() || nameField.name.length > 80 || nameField.value.length !in 1..maximumLength ||
            maximumLength !in 1..16 || containsUnsafeRecruitmentNameCharacter(nameField.value)
        ) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "캐릭터 이름은 1~16자로 입력해 주세요.")
        }
        val guarded = actionGuard.guard(currentPage, action)
        val submit = guarded.form.submitFields.singleOrNull()
        if (submit == null || !submit.name.equals("Recruit", true) ||
            !Regex("(?:Recruit|모집|고용)", RegexOption.IGNORE_CASE).matches(submit.value.trim())
        ) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 모집 양식이 변경되었습니다.")
        val document = Jsoup.parse(current.body, current.finalUrl)
        val matchingForms = document.select("form").filter { domForm ->
            val semantic = formParser.parse(domForm.outerHtml(), current.finalUrl).forms
            semantic.any { it.actionId == guarded.form.actionId && it.method == guarded.form.method && it.actionUrl == guarded.form.actionUrl }
        }
        if (matchingForms.size != 1) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 모집 양식을 안전하게 확인하지 못했습니다.")
        val controlsWithName = matchingForms.single().select("input,select,textarea").filter { control ->
            !control.hasAttr("disabled") && control.attr("name") == nameField.name
        }
        if (controlsWithName.size != 1) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 이름 입력란이 변경되었습니다.")
        val inputs = controlsWithName.filter { input ->
            input.tagName() == "input" &&
                input.attr("type").lowercase() in setOf("", "text") &&
                input.attr("maxlength").toIntOrNull() == maximumLength && maximumLength <= 16 &&
                !input.hasAttr("readonly") && !input.attr("style").contains("display:none", true)
        }
        if (inputs.size != 1) throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 이름 입력란이 변경되었습니다.")
        val input = inputs.single()
        val meaning = listOf(
            input.attr("name"), input.id(), input.attr("placeholder"), input.attr("title"),
            input.closest("label")?.text().orEmpty(), input.parent()?.text().orEmpty(),
        ).joinToString(" ")
        if (!Regex("name|이름|성명", RegexOption.IGNORE_CASE).containsMatchIn(meaning)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 이름 입력란을 확인하지 못했습니다.")
        }
        val entries = guarded.formEntries.toMutableList()
        val submitStart = entries.indexOfFirst { field -> guarded.form.submitFields.any { it.name == field.name && it.value == field.value } }
            .let { if (it < 0) entries.size else it }
        entries.add(submitStart, nameField)
        val response = executeAuthenticated(
            context.account,
            requestFactory.townForm(guarded.form.method, guarded.form.actionUrl, entries, HofRequestOrigin.INTERACTIVE),
            context.cookies + current.setCookies,
        )
        val result = resultParser.parse(response.body)
        val page = formParser.parse(response.body, response.finalUrl)
        projector(response.body, response.finalUrl, result, page)
    }

    private fun containsUnsafeRecruitmentNameCharacter(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.SURROGATE.toInt() || type == Character.PRIVATE_USE.toInt()
            ) return true
            offset += Character.charCount(codePoint)
        }
        return false
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

    /**
     * 파괴적 최종 action 없이 HOF의 선택 1단계만 실행하고 그 응답을 구조화한다.
     * 카드 강화/변화처럼 베이스 선택 후에만 재료 후보가 나타나는 form에 사용한다.
     */
    fun <T> loadSecondStageProjected(
        accountId: Long,
        pageUrl: String,
        requiredEntrySubmitField: String,
        entryAction: (String, String, ParsedTownPage) -> TownActionRequest,
        projector: (String, String, ParsedTownPage) -> T,
    ): T = withAccountActionFence(accountId) {
        val context = authenticatedContext(accountId)
        val main = executeAuthenticated(
            context.account,
            requestFactory.townPage(pageUrl, HofRequestOrigin.INTERACTIVE),
            context.cookies,
        )
        val mainPage = formParser.parse(main.body, main.finalUrl)
        val guardedEntry = actionGuard.guard(mainPage, entryAction(main.body, main.finalUrl, mainPage))
        if (guardedEntry.form.submitFields.singleOrNull()?.name != requiredEntrySubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 1단계 작업 양식이 변경되었습니다.")
        }
        val entryResponse = executeAuthenticated(
            context.account,
            requestFactory.townForm(
                guardedEntry.form.method,
                guardedEntry.form.actionUrl,
                guardedEntry.formEntries,
                HofRequestOrigin.INTERACTIVE,
            ),
            context.cookies + main.setCookies,
        )
        val entryPage = formParser.parse(entryResponse.body, entryResponse.finalUrl)
        projector(entryResponse.body, entryResponse.finalUrl, entryPage)
    }

    /** 최신 GET과 그 응답의 두 form을 같은 계정 fence 안에서 각각 다시 해석하는 variant다. */
    fun <T> executeResolvedTwoStepProjectedWithScalars(
        accountId: Long,
        pageUrl: String,
        requiredEntrySubmitField: String,
        entryAction: (String, String, ParsedTownPage) -> TownActionRequest,
        finalAction: (String, String, ParsedTownPage) -> TownActionRequest,
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
        val guardedEntry = actionGuard.guard(mainPage, entryAction(main.body, main.finalUrl, mainPage))
        if (guardedEntry.form.submitFields.singleOrNull()?.name != requiredEntrySubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 1단계 작업 양식이 변경되었습니다.")
        }
        val entryResponse = executeAuthenticated(
            context.account,
            requestFactory.townForm(guardedEntry.form.method, guardedEntry.form.actionUrl, guardedEntry.formEntries, HofRequestOrigin.INTERACTIVE),
            context.cookies + main.setCookies,
        )
        val entryPage = formParser.parse(entryResponse.body, entryResponse.finalUrl)
        val guardedFinal = actionGuard.guard(entryPage, finalAction(entryResponse.body, entryResponse.finalUrl, entryPage))
        if (guardedFinal.form.submitFields.singleOrNull()?.name != requiredFinalSubmitField) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "현재 HOF 최종 작업 양식이 변경되었습니다.")
        }
        val matchingForms = Jsoup.parse(entryResponse.body, entryResponse.finalUrl).select("form").filter { domForm ->
            val controls = domForm.select("input,button,select,textarea")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
            val names = controls.map { it.attr("name") }.toSet()
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
