package app.spammy.hof.town.fishing.service

import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.common.service.TownObservedPageContinuation
import app.spammy.hof.town.common.service.TownSubmissionBoundary
import app.spammy.hof.town.fishing.dto.FishingExchangeRequest
import app.spammy.hof.town.fishing.dto.FishingExchangeResponse
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingBattleTarget
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.model.FishingSnapshot
import app.spammy.hof.town.fishing.parser.FishingExchangeCategoryException
import app.spammy.hof.town.fishing.parser.FishingExchangeContractException
import app.spammy.hof.town.fishing.parser.FishingPageParser
import org.springframework.stereotype.Service

@Service
class FishingService(
    private val executor: TownAuthenticatedExecutor,
    private val locationResolver: TownLocationResolver,
    private val parser: FishingPageParser,
    private val battleMaps: BattleMapService,
) {
    fun load(
        accountId: Long,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.loadProjected(accountId, url, origin) { html, finalUrl, page ->
            FishingResponse.from(withObservedBattleTarget(accountId, parser.parse(html, finalUrl, page), origin))
        }
    }

    fun loadForAutomation(accountId: Long): FishingAutomationObservation {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        val observed = executor.loadContinuableProjected(
            accountId,
            url,
            HofRequestOrigin.AUTOMATION,
        ) { html, finalUrl, page ->
            val snapshot = parser.parse(html, finalUrl, page)
            FishingResponse.from(
                if (snapshot.primaryAction in setOf(FishingPrimaryAction.START, FishingPrimaryAction.CATCH)) {
                    snapshot
                } else {
                    withObservedBattleTarget(accountId, snapshot, HofRequestOrigin.AUTOMATION)
                },
            )
        }
        return FishingAutomationObservation(observed.value, observed.continuation)
    }

    fun act(
        accountId: Long,
        action: FishingAction,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            origin = origin,
            resolveAction = { html, finalUrl, page -> resolveFishingAction(html, finalUrl, page, action) },
        ) { html, finalUrl, result, page ->
            FishingResponse.from(withObservedBattleTarget(accountId, parser.parse(html, finalUrl, page, result), origin))
        }
    }

    fun executeOneCastForAutomation(
        accountId: Long,
        observation: FishingAutomationObservation? = null,
        submissionBoundary: TownSubmissionBoundary = TownSubmissionBoundary { it() },
        beforeCatchSubmission: (FishingResponse) -> Unit,
    ): FishingOneCastRemoteResult {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        var started: FishingResponse? = null
        val caught = executor.executeObservedResponseTwoStepProjected(
            accountId = accountId,
            pageUrl = url,
            origin = HofRequestOrigin.AUTOMATION,
            observation = observation?.continuation,
            entryAction = { html, finalUrl, page ->
                resolveFishingAction(html, finalUrl, page, FishingAction.START)
            },
            expectedEntryForm = { form -> parser.actionFor(form) == FishingAction.START },
            observeEntryResponse = { html, finalUrl, result, page ->
                started = FishingResponse.from(parser.parse(html, finalUrl, page, result))
            },
            finalAction = { html, finalUrl, page ->
                resolveFishingActionOrNull(html, finalUrl, page, FishingAction.CATCH)
            },
            expectedFinalForm = { form -> parser.actionFor(form) == FishingAction.CATCH },
            beforeFinalSubmission = {
                beforeCatchSubmission(requireNotNull(started))
            },
            entrySubmissionBoundary = submissionBoundary,
            finalSubmissionBoundary = submissionBoundary,
        ) { html, finalUrl, result, page ->
            FishingResponse.from(parser.parse(html, finalUrl, page, result))
        }
        val startResponse = requireNotNull(started) {
            "Fishing START response was not observed after selecting a fishing cycle."
        }
        return caught?.let { FishingOneCastRemoteResult.Completed(startResponse, it) }
            ?: FishingOneCastRemoteResult.WaitingForCatch(startResponse)
    }

    fun executeObservedActionForAutomation(
        accountId: Long,
        action: FishingAction,
        observation: FishingAutomationObservation,
    ): FishingResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING).url
        return executor.executeObservedProjected(
            accountId = accountId,
            pageUrl = url,
            origin = HofRequestOrigin.AUTOMATION,
            observation = observation.continuation,
            resolveAction = { html, finalUrl, page ->
                resolveFishingAction(html, finalUrl, page, action)
            },
            expectedForm = { form -> parser.actionFor(form) == action },
        ) { html, finalUrl, result, page ->
            FishingResponse.from(parser.parse(html, finalUrl, page, result))
        }
    }

    fun loadExchange(accountId: Long): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.loadProjected(accountId, url) { html, finalUrl, page ->
            FishingExchangeResponse.from(parseExchange(html, finalUrl, page))
        }
    }

    fun loadExchangeCategory(accountId: Long, categoryCandidateId: String): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.loadProjected(accountId, url) { html, finalUrl, page ->
            FishingExchangeResponse.from(parseExchange(html, finalUrl, page, categoryCandidateId = categoryCandidateId))
        }
    }

    fun exchange(accountId: Long, request: FishingExchangeRequest): FishingExchangeResponse {
        val url = locationResolver.resolve(TownFeatureId.FISHING_EXCHANGE).url
        return executor.executeMaterializedResolvedProjectedWithScalars(
            accountId = accountId,
            pageUrl = url,
            requiredScalarFields = setOf("ItemT", "amount"),
            requiredSubmitField = "Create",
            requiredSyntheticFields = setOf("ItemNo", "list_type"),
            materialize = { html, finalUrl -> materializeExchange(html, finalUrl, request.categoryCandidateId) },
            resolve = { html, finalUrl, page -> resolveExchangeAction(html, finalUrl, page, request) },
        ) { html, finalUrl, result, page ->
            FishingExchangeResponse.from(
                parseExchange(html, finalUrl, page, result, request.categoryCandidateId),
            )
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

    private fun resolveFishingActionOrNull(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        action: FishingAction,
    ): TownActionRequest? {
        val state = parser.parse(html, finalUrl, page)
        if (state.blockedByBattle) return null
        return state.availableActions.singleOrNull { it.action == action }
            ?.let { TownActionRequest(actionId = it.actionId) }
    }

    private fun resolveExchangeAction(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        request: FishingExchangeRequest,
    ): Pair<TownActionRequest, Map<String, String>> {
        val snapshot = parseExchange(html, finalUrl, page, categoryCandidateId = request.categoryCandidateId)
        if (snapshot.currentCategoryId != request.categoryCandidateId) {
            unavailable("현재 표시된 낚시 교환 분류를 다시 확인해 주세요.")
        }
        val item = snapshot.items.singleOrNull { it.id == request.candidateId && it.selectable }
            ?: unavailable("현재 교환할 수 없는 품목입니다. 새로고침 후 다시 시도해 주세요.")
        return TownActionRequest(
            actionId = snapshot.actionId ?: unavailable("현재 낚시 교환 양식을 찾지 못했습니다."),
            selections = listOf(TownActionSelection(request.candidateId)),
        ) to mapOf(
            "ItemT" to (item.itemT ?: unavailable("현재 교환품의 HOF 계약을 확인하지 못했습니다.")),
            "amount" to request.quantity.toString(),
        )
    }

    /**
     * 실서버는 낚시 전투가 발생해도 낚시 페이지에 경고나 전투 링크를 항상 표시하지 않는다.
     * 전투 탭의 이번 응답에서 실제로 관측된 `낚시` 그룹 맵을 권위 있는 차단 상태로 사용한다.
     */
    private fun withObservedBattleTarget(
        accountId: Long,
        snapshot: FishingSnapshot,
        origin: HofRequestOrigin,
    ): FishingSnapshot {
        if (snapshot.battleTarget != null) return snapshot

        val observed = battleMaps.findCurrentlyObservedMaps(accountId, BATTLE_CATEGORY_ID, origin)
            .filter { it.enabled && it.resolved && !it.mapCode.isNullOrBlank() }
            .filter(::isFishingBattleMap)
            .firstOrNull() ?: return snapshot
        return snapshot.copy(
            primaryAction = FishingPrimaryAction.NONE,
            availableActions = emptyList(),
            blockedByBattle = true,
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

    private fun parseExchange(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
        categoryCandidateId: String? = null,
    ) = try {
        parser.parseExchange(html, finalUrl, page, result, categoryCandidateId)
    } catch (_: FishingExchangeCategoryException) {
        unavailable("현재 HOF에서 선택할 수 없는 낚시 교환 분류입니다.")
    } catch (_: FishingExchangeContractException) {
        throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "현재 낚시 교환소 응답 형식을 확인할 수 없습니다.")
    }

    private fun materializeExchange(html: String, finalUrl: String, categoryCandidateId: String): String = try {
        parser.materializeExchangeHtml(html, finalUrl, categoryCandidateId)
    } catch (_: FishingExchangeCategoryException) {
        unavailable("현재 HOF에서 선택할 수 없는 낚시 교환 분류입니다.")
    } catch (_: FishingExchangeContractException) {
        throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "현재 낚시 교환소 응답 형식을 확인할 수 없습니다.")
    }

    private companion object {
        const val BATTLE_CATEGORY_ID = "battle_map"
    }
}

sealed interface FishingOneCastRemoteResult {
    data class Completed(
        val start: FishingResponse,
        val catch: FishingResponse,
    ) : FishingOneCastRemoteResult

    data class WaitingForCatch(
        val start: FishingResponse,
    ) : FishingOneCastRemoteResult
}

class FishingAutomationObservation internal constructor(
    val response: FishingResponse,
    internal val continuation: TownObservedPageContinuation,
)
