package app.spammy.hof.town.fishing.service

import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.service.BattleMapService
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
import app.spammy.hof.town.fishing.model.FishingBattleTarget
import app.spammy.hof.town.fishing.model.FishingSnapshot
import app.spammy.hof.town.fishing.parser.FishingPageParser
import org.springframework.stereotype.Service

@Service
class FishingService(
    private val executor: TownAuthenticatedExecutor,
    private val locationResolver: TownLocationResolver,
    private val parser: FishingPageParser,
    private val battleMaps: BattleMapService,
) {
    fun load(accountId: Long): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.loadProjected(accountId, url) { html, finalUrl, page ->
            FishingResponse.from(withObservedBattleTarget(accountId, parser.parse(html, finalUrl, page)))
        }
    }

    fun act(accountId: Long, action: FishingAction): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            resolveAction = { html, finalUrl, page -> resolveFishingAction(html, finalUrl, page, action) },
        ) { html, finalUrl, result, page ->
            FishingResponse.from(withObservedBattleTarget(accountId, parser.parse(html, finalUrl, page, result)))
        }
    }

    fun loadExchange(accountId: Long): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.loadProjected(accountId, url) { html, finalUrl, page ->
            FishingExchangeResponse.from(parser.parseExchange(html, finalUrl, page))
        }
    }

    fun loadExchangeCategory(accountId: Long, categoryCandidateId: String): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.loadSelectedOptionProjected(
            accountId = accountId,
            pageUrl = url,
            actionId = { html, finalUrl, page ->
                parser.parseExchange(html, finalUrl, page).actionId
                    ?: unavailable("현재 낚시 교환소의 품목 분류 양식을 찾지 못했습니다.")
            },
            optionCandidateId = categoryCandidateId,
            requiredOptionField = "type_create",
            requiredFormSubmitField = "Create",
            excludedActionFields = setOf("Create", "ItemNo", "ItemT", "amount"),
        ) { html, finalUrl, page ->
            val snapshot = parser.parseExchange(html, finalUrl, page)
            if (snapshot.currentCategoryId != categoryCandidateId) {
                unavailable("HOF가 요청한 낚시 교환 분류로 전환하지 않았습니다.")
            }
            FishingExchangeResponse.from(snapshot)
        }
    }

    fun exchange(accountId: Long, request: FishingExchangeRequest): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.executeResolvedProjectedWithScalars(
            accountId = accountId,
            pageUrl = url,
            requiredScalarFields = setOf("ItemT", "amount"),
            requiredSubmitField = "Create",
            resolve = { html, finalUrl, page -> resolveExchangeAction(html, finalUrl, page, request) },
        ) { html, finalUrl, result, page ->
            FishingExchangeResponse.from(parser.parseExchange(html, finalUrl, page, result))
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
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        request: FishingExchangeRequest,
    ): Pair<TownActionRequest, Map<String, String>> {
        val snapshot = parser.parseExchange(html, finalUrl, page)
        if (snapshot.currentCategoryId != request.categoryCandidateId) {
            unavailable("현재 표시된 낚시 교환 분류를 다시 확인해 주세요.")
        }
        val item = snapshot.items.singleOrNull { it.id == request.candidateId && it.selectable }
            ?: unavailable("현재 교환할 수 없는 품목입니다. 새로고침 후 다시 시도해 주세요.")
        return TownActionRequest(
            actionId = snapshot.actionId ?: unavailable("현재 낚시 교환 양식을 찾지 못했습니다."),
            selections = listOf(
                TownActionSelection(request.categoryCandidateId),
                TownActionSelection(request.candidateId),
            ),
        ) to mapOf(
            "ItemT" to (item.itemT ?: unavailable("현재 교환품의 HOF 계약을 확인하지 못했습니다.")),
            "amount" to request.quantity.toString(),
        )
    }

    /**
     * 실서버의 낚시 경고에는 전투 링크가 없을 수 있다. 그 경우 전투 탭의 이번 응답에서 실제로 관측된
     * `낚시` 그룹 맵을 찾아 전투 화면으로 이동할 수 있는 목표를 보완한다.
     */
    private fun withObservedBattleTarget(accountId: Long, snapshot: FishingSnapshot): FishingSnapshot {
        if (!snapshot.blockedByBattle || snapshot.battleTarget != null) return snapshot

        val observed = battleMaps.findCurrentlyObservedMaps(accountId, BATTLE_CATEGORY_ID)
            .filter { it.enabled && it.resolved && !it.mapCode.isNullOrBlank() }
            .filter(::isFishingBattleMap)
            .firstOrNull() ?: return snapshot
        return snapshot.copy(
            battleTarget = FishingBattleTarget(
                categoryId = BATTLE_CATEGORY_ID,
                mapCode = requireNotNull(observed.mapCode),
                name = observed.name,
            ),
        )
    }

    private fun isFishingBattleMap(map: BattleMapResponse): Boolean =
        map.groupName?.trim()?.startsWith("낚시") == true ||
            map.name.trim().startsWith("Fishing-", ignoreCase = true)

    private fun unavailable(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private companion object {
        const val BATTLE_CATEGORY_ID = "battle_map"
    }
}
