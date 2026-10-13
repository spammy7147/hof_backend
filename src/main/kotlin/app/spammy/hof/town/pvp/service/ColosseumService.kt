package app.spammy.hof.town.pvp.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.pvp.dto.*
import app.spammy.hof.town.pvp.parser.ColosseumParser
import org.springframework.stereotype.Service

@Service
class ColosseumService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: ColosseumParser,
) {
    fun loadBattle(accountId: Long): ColosseumBattleResponse = executor.loadProjected(accountId, battleUrl()) { html, finalUrl, page ->
        ColosseumBattleResponse.from(parser.parseBattle(html, finalUrl, page))
    }

    fun saveTeam(accountId: Long, request: SaveColosseumTeamRequest): ColosseumBattleResponse {
        val saved = executor.executeProjectedWithSingleFallbackGet(
            accountId = accountId,
            pageUrl = battleUrl(),
            resolveAction = { html, finalUrl, page ->
                val snapshot = parser.parseBattle(html, finalUrl, page)
                val requested = request.fighterCandidateIds.distinct()
                if (requested.size != request.fighterCandidateIds.size || requested.size != 5) {
                    invalid("콜로세움에 출전할 5명을 중복 없이 선택해 주세요.")
                }
                if (!snapshot.fighters.map { it.id }.toSet().containsAll(requested)) invalid("현재 선택할 수 없는 팀원이 포함되어 있습니다.")
                TownActionRequest(snapshot.teamActionId ?: invalid("현재 팀 저장 양식을 찾지 못했습니다."),
                    requested.map { TownActionSelection(snapshot.teamCandidateIds.getValue(it)) })
            },
            acceptsActionResponse = { it.fighters.isNotEmpty() },
        ) { html, finalUrl, result, page, _ -> parser.parseBattle(html, finalUrl, page, result) }
        if (saved.selectedTeam.toSet() != request.fighterCandidateIds.toSet()) invalid("HOF가 요청한 팀을 저장하지 않았습니다.")
        return ColosseumBattleResponse.from(saved)
    }

    /** 저장된 팀은 HOF 서버 상태를 그대로 사용하며 앱에서 팀 payload를 다시 만들지 않는다. */
    fun challenge(accountId: Long): ColosseumBattleResponse = executor.executeProjected(
        accountId = accountId,
        pageUrl = battleUrl(),
        resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parseBattle(html, finalUrl, page)
            if (snapshot.selectedTeam.size != 5) invalid("콜로세움에 출전할 5명을 먼저 저장해 주세요.")
            val actionId = snapshot.challengeActionId
                ?: invalid(snapshot.challengeNotice ?: "현재 랭킹에 도전할 수 없습니다. 새로고침 후 다시 시도해 주세요.")
            TownActionRequest(actionId)
        },
    ) { html, finalUrl, result, page ->
        val parsed = parser.parseBattle(html, finalUrl, page, result)
        if (parsed.battleResult == null) invalid("콜로세움 전투 결과를 확인하지 못했습니다.")
        ColosseumBattleResponse.from(parsed)
    }

    fun loadShop(accountId: Long): ColosseumShopResponse = executor.loadProjected(accountId, shopUrl()) { html, finalUrl, page ->
        ColosseumShopResponse.from(parser.parseShop(html, finalUrl, page))
    }

    fun loadShopCategory(accountId: Long, categoryCandidateId: String): ColosseumShopResponse =
        executor.loadResolvedSelectedOptionProjected(
            accountId = accountId,
            pageUrl = shopUrl(),
            optionCandidateId = categoryCandidateId,
            excludedActionFields = setOf("Create", "create", "Trade", "trade", "amount", "suu", "ItemT", "item_no", "list_type"),
            resolveContract = { html, finalUrl, page ->
                val snapshot = parser.parseShop(html, finalUrl, page)
                val form = page.forms.singleOrNull { it.actionId == snapshot.actionId } ?: invalid("현재 교환 양식을 찾지 못했습니다.")
                Triple(snapshot.actionId ?: invalid("현재 교환 양식을 찾지 못했습니다."), snapshot.categoryField ?: invalid("현재 분류 선택란을 찾지 못했습니다."), form.submitFields.singleOrNull()?.name ?: invalid("현재 교환 버튼을 확인하지 못했습니다."))
            },
        ) { html, finalUrl, page ->
            val snapshot = parser.parseShop(html, finalUrl, page)
            if (snapshot.currentCategoryId != categoryCandidateId) invalid("HOF가 요청한 분류로 전환하지 않았습니다.")
            ColosseumShopResponse.from(snapshot)
        }

    fun trade(accountId: Long, request: ColosseumTradeRequest): ColosseumShopResponse = executor.executeResolvedProjectedWithScalars(
        accountId = accountId,
        pageUrl = shopUrl(),
        requiredScalarFields = setOf("ItemT", "list_type", "amount"),
        requiredSubmitField = "Create",
        resolve = { html, finalUrl, page ->
            val snapshot = parser.parseShop(html, finalUrl, page)
            if (snapshot.currentCategoryId != request.categoryCandidateId) invalid("현재 표시된 교환 분류를 다시 확인해 주세요.")
            val row = snapshot.items.singleOrNull { it.id == request.candidateId && it.selectable }
                ?: invalid("현재 선택할 수 없는 교환 품목입니다.")
            val form = page.forms.singleOrNull { it.actionId == snapshot.actionId } ?: invalid("현재 교환 양식을 찾지 못했습니다.")
            val candidate = form.candidates.singleOrNull { it.id == row.id } ?: invalid("현재 교환 품목 계약을 확인하지 못했습니다.")
            val max = row.maxQuantity ?: Int.MAX_VALUE
            if (request.quantity !in row.minQuantity..max) invalid("현재 HOF가 허용하는 수량을 입력해 주세요.")
            val categoryId = snapshot.currentCategoryId ?: invalid("현재 교환 분류를 확인하지 못했습니다.")
            val category = form.candidates.singleOrNull { it.id == categoryId } ?: invalid("현재 교환 분류 계약을 확인하지 못했습니다.")
            TownActionRequest(snapshot.actionId ?: invalid("현재 교환 양식을 찾지 못했습니다."), listOf(TownActionSelection(candidate.id))) to mapOf(
                "ItemT" to (row.itemT ?: invalid("현재 품목의 HOF ItemT 계약을 확인하지 못했습니다.")),
                "list_type" to category.inputValue,
                "amount" to request.quantity.toString(),
            )
        },
    ) { html, finalUrl, result, page -> ColosseumShopResponse.from(parser.parseShop(html, finalUrl, page, result)) }

    private fun battleUrl() = locations.resolve(TownFeatureId.COLOSSEUM_BATTLE).url
    private fun shopUrl() = locations.resolve(TownFeatureId.COLOSSEUM_EXCHANGE).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
