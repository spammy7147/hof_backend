package app.spammy.hof.town.pantheon.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.common.service.TownObservedAction
import app.spammy.hof.town.pantheon.dto.*
import app.spammy.hof.town.pantheon.model.PantheonAction
import app.spammy.hof.town.pantheon.parser.PantheonParser
import org.springframework.stereotype.Service

@Service
class PantheonService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: PantheonParser,
) {
    fun street(accountId: Long): PantheonStreetResponse {
        val street = loadStreet(accountId)
        val actions = street.shrines.associate { shrine ->
            shrine.id to executor.loadProjected(accountId, shrine.detailUrl) { html, finalUrl, page ->
                parser.parseDetail(shrine.id, html, finalUrl, page).actions
            }
        }
        return PantheonStreetResponse.from(street, actions)
    }

    fun detail(accountId: Long, shrineId: String): PantheonDetailResponse {
        val shrine = loadStreet(accountId).shrines.singleOrNull { it.id == shrineId }
            ?: invalid("현재 HOF 신전 거리에서 해당 신전을 찾지 못했습니다.")
        val detail = executor.loadProjected(accountId, shrine.detailUrl) { html, finalUrl, page ->
            parser.parseDetail(shrine.id, html, finalUrl, page)
        }
        return PantheonDetailResponse.from(detail)
    }

    fun action(accountId: Long, shrineId: String, request: PantheonActionRequest): PantheonDetailResponse {
        val projected = executor.executeNestedObservedActionProjected(
            accountId = accountId,
            rootPageUrl = streetUrl(),
            resolveDetailUrl = { html, finalUrl, _ ->
                parser.parseStreet(html, finalUrl).shrines.singleOrNull { it.id == shrineId }?.detailUrl
                    ?: invalid("현재 HOF 신전 거리에서 해당 신전을 찾지 못했습니다.")
            },
            resolveAction = { html, finalUrl, page ->
                val action = currentAction(shrineId, request.actionId, html, finalUrl, page)
                when {
                    action.formActionId != null -> TownObservedAction.Form(TownActionRequest(action.formActionId))
                    action.query != null -> TownObservedAction.Link(action.query)
                    else -> invalid("현재 HOF 신전 동작을 안전하게 확인하지 못했습니다.")
                }
            },
            projector = { html, finalUrl, result, page -> parser.parseDetail(shrineId, html, finalUrl, page, result) },
        )
        return PantheonDetailResponse.from(projected)
    }

    private fun currentAction(shrineId: String, actionId: String, html: String, finalUrl: String, page: app.spammy.hof.town.common.model.ParsedTownPage): PantheonAction =
        parser.parseDetail(shrineId, html, finalUrl, page).actions.singleOrNull { it.id == actionId }
            ?: invalid("현재 이 신전에서 해당 동작을 확인할 수 없습니다.")

    private fun loadStreet(accountId: Long) = executor.loadProjected(accountId, streetUrl()) { html, finalUrl, _ ->
        parser.parseStreet(html, finalUrl)
    }
    private fun streetUrl() = locations.resolve(TownFeatureId.PANTHEON).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
