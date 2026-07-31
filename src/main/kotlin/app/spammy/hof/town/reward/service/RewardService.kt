package app.spammy.hof.town.reward.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.reward.dto.*
import app.spammy.hof.town.reward.model.OrbExchangeSnapshot
import app.spammy.hof.town.reward.parser.OrbExchangeParser
import app.spammy.hof.town.reward.parser.StashPageParser
import org.springframework.stereotype.Service

@Service
class RewardService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val stashParser: StashPageParser,
    private val orbParser: OrbExchangeParser,
) {
    fun loadStash(accountId: Long): StashResponse = executor.loadProjected(accountId, url(TownFeatureId.STASH)) { html, finalUrl, page ->
        StashResponse.from(stashParser.parse(html, finalUrl, page))
    }

    fun openStash(accountId: Long, request: StashOpenRequest): StashResponse = executor.executeProjected(
        accountId = accountId,
        pageUrl = url(TownFeatureId.STASH),
        resolveAction = { html, finalUrl, page ->
            val snapshot = stashParser.parse(html, finalUrl, page)
            val action = snapshot.actions.singleOrNull { it.action == request.action }
                ?: invalid("현재 HOF가 제공하는 ${request.action.label()} action을 확인하지 못했습니다.")
            val box = snapshot.boxes.singleOrNull { it.id == request.boxCandidateId && it.selectable }
                ?: invalid("현재 개봉할 수 없는 상자입니다. 목록을 갱신해 주세요.")
            TownActionRequest(action.actionId, listOf(TownActionSelection(box.id)))
        },
    ) { html, finalUrl, result, page -> StashResponse.from(stashParser.parse(html, finalUrl, page, result)) }

    fun loadOrbs(accountId: Long): OrbExchangeResponse = executor.loadProjected(accountId, url(TownFeatureId.ORB_EXCHANGE)) { html, finalUrl, page ->
        OrbExchangeResponse.from(orbParser.parse(html, finalUrl, page))
    }

    fun exchangeOrbs(accountId: Long, request: OrbExchangeRequest): OrbExchangeResponse {
        lateinit var before: OrbExchangeSnapshot
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url(TownFeatureId.ORB_EXCHANGE),
            resolveAction = { html, finalUrl, page ->
                before = orbParser.parse(html, finalUrl, page)
                val action = before.actions.singleOrNull { it.action == request.action }
                    ?: invalid("현재 HOF 오브 교환 action을 확인하지 못했습니다. 목록을 갱신해 주세요.")
                TownActionRequest(action.actionId)
            },
        ) { html, finalUrl, result, page ->
            OrbExchangeResponse.from(orbParser.parseExchange(html, finalUrl, page, result, before, request.action.repetitions))
        }
    }

    private fun url(feature: TownFeatureId) = locations.resolve(feature).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private fun app.spammy.hof.town.reward.model.StashOpenAction.label() = when (this) {
        app.spammy.hof.town.reward.model.StashOpenAction.ONE -> "1개"
        app.spammy.hof.town.reward.model.StashOpenAction.TWENTY -> "20개"
        app.spammy.hof.town.reward.model.StashOpenAction.HUNDRED -> "100개"
        app.spammy.hof.town.reward.model.StashOpenAction.THOUSAND -> "1000개"
        app.spammy.hof.town.reward.model.StashOpenAction.ALL -> "전부"
    }
}
