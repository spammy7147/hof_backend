package app.spammy.hof.town.fishing.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.fishing.dto.FishingExchangeRequest
import app.spammy.hof.town.fishing.dto.FishingExchangeResponse
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.parser.FishingPageParser
import org.springframework.stereotype.Service

@Service
class FishingService(
    private val executor: TownAuthenticatedExecutor,
    private val locationResolver: TownLocationResolver,
    private val parser: FishingPageParser,
) {
    fun load(accountId: Long): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.loadProjected(accountId, url) { html, finalUrl, page ->
            FishingResponse.from(parser.parse(html, finalUrl, page))
        }
    }

    fun act(accountId: Long, action: FishingAction): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            resolveAction = { html, finalUrl, page -> resolveFishingAction(html, finalUrl, page, action) },
        ) { html, finalUrl, result, page ->
            FishingResponse.from(parser.parse(html, finalUrl, page, result))
        }
    }

    fun loadExchange(accountId: Long): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.loadProjected(accountId, url) { _, _, page ->
            FishingExchangeResponse.from(parser.parseExchange(page))
        }
    }

    fun exchange(accountId: Long, request: FishingExchangeRequest): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            resolveAction = { _, _, page -> resolveExchangeAction(page, request) },
        ) { _, _, result, page ->
            FishingExchangeResponse.from(parser.parseExchange(page, result))
        }
    }

    private fun resolveFishingAction(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        action: FishingAction,
    ): TownActionRequest {
        val state = parser.parse(html, finalUrl, page)
        if (state.blockedByBattle) unavailable("낚시 전투를 마친 뒤 낚시를 계속할 수 있습니다.")
        val actionId = state.availableActions.singleOrNull { it.action == action }?.actionId
            ?: unavailable("현재 낚시 상태에서는 ${action.name} 작업을 실행할 수 없습니다.")
        return TownActionRequest(actionId = actionId)
    }

    private fun resolveExchangeAction(
        page: ParsedTownPage,
        request: FishingExchangeRequest,
    ): TownActionRequest {
        val form = page.forms.singleOrNull { form -> form.candidates.any { it.id == request.candidateId } }
            ?: unavailable("현재 교환할 수 없는 품목입니다. 새로고침 후 다시 시도해 주세요.")
        return TownActionRequest(
            actionId = form.actionId,
            selections = listOf(TownActionSelection(request.candidateId, request.quantity)),
        )
    }

    private fun unavailable(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
