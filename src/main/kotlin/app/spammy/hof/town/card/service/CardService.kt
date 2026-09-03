package app.spammy.hof.town.card.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.card.dto.*
import app.spammy.hof.town.card.parser.CardPageParser
import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import org.springframework.stereotype.Service

@Service
class CardService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: CardPageParser,
) {
    fun loadIdentify(accountId: Long): CardIdentifyResponse = executor.loadProjected(accountId, url(TownFeatureId.CARD_IDENTIFY)) { html, finalUrl, page ->
        CardIdentifyResponse.from(parser.parseIdentify(html, finalUrl, page))
    }

    fun identify(accountId: Long, request: CardIdentifyRequest): CardIdentifyResponse = executor.executeProjected(
        accountId, url(TownFeatureId.CARD_IDENTIFY),
        resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parseIdentify(html, finalUrl, page)
            TownActionRequest(requireAction(snapshot.actionId), listOf(TownActionSelection(request.candidateId)))
        },
    ) { html, finalUrl, result, page -> CardIdentifyResponse.from(parser.parseIdentify(html, finalUrl, page, result)) }

    fun loadUpgrade(accountId: Long): CardUpgradeResponse = executor.loadProjected(accountId, url(TownFeatureId.CARD_UPGRADE)) { html, finalUrl, page ->
        CardUpgradeResponse.from(parser.parseUpgrade(html, finalUrl, page))
    }

    fun loadUpgradeOptions(accountId: Long, request: CardBaseOptionsRequest): CardUpgradeResponse = loadOptions(
        accountId, TownFeatureId.CARD_UPGRADE, request.baseCandidateId,
    )

    fun upgrade(accountId: Long, request: CardUpgradeRequest): CardUpgradeResponse {
        distinctCards(request.baseCandidateId, request.materialCandidateId)
        val pageUrl = url(TownFeatureId.CARD_UPGRADE)
        return executor.executeResolvedProjectedWithScalars(
            accountId, pageUrl,
            requiredScalarFields = setOf("amount"), requiredSubmitField = "Create",
            resolve = { html, finalUrl, page ->
                val snapshot = parser.parseUpgrade(html, finalUrl, page)
                requireCombinedStage(snapshot.baseCards, snapshot.materialCards)
                val base = validateBase(snapshot.baseCards, request.baseCandidateId)
                val material = validateMaterial(snapshot.materialCards, request.materialCandidateId)
                validateQuantity(request.quantity, snapshot.minQuantity, selectedMax(snapshot.maxQuantity, base.owned, material.owned))
                TownActionRequest(
                    requireAction(snapshot.actionId),
                    listOf(TownActionSelection(request.baseCandidateId), TownActionSelection(request.materialCandidateId)),
                ) to mapOf("amount" to request.quantity.toString())
            },
        ) { html, finalUrl, result, page -> CardUpgradeResponse.from(parser.parseUpgrade(html, finalUrl, page, result).copy(selectedBaseCandidateId = request.baseCandidateId)) }
    }

    fun loadChange(accountId: Long): CardChangeResponse = executor.loadProjected(accountId, url(TownFeatureId.CARD_CHANGE)) { html, finalUrl, page ->
        CardChangeResponse.from(parser.parseChange(html, finalUrl, page))
    }

    fun loadChangeOptions(accountId: Long, request: CardBaseOptionsRequest): CardChangeResponse = loadChangeOptions(
        accountId, TownFeatureId.CARD_CHANGE, request.baseCandidateId,
    )

    fun change(accountId: Long, request: CardChangeRequest): CardChangeResponse {
        distinctCards(request.baseCandidateId, request.materialCandidateId)
        val pageUrl = url(TownFeatureId.CARD_CHANGE)
        return executor.executeResolvedProjectedWithScalars(
            accountId, pageUrl,
            requiredScalarFields = setOf("amount"), requiredSubmitField = "Create",
            resolve = { html, finalUrl, page ->
                val snapshot = parser.parseChange(html, finalUrl, page)
                requireCombinedStage(snapshot.baseCards, snapshot.materialCards)
                val base = validateBase(snapshot.baseCards, request.baseCandidateId)
                val material = validateMaterial(snapshot.materialCards, request.materialCandidateId)
                validateQuantity(request.quantity, snapshot.minQuantity, selectedMax(snapshot.maxQuantity, base.owned, material.owned))
                TownActionRequest(
                    requireAction(snapshot.actionId),
                    listOf(TownActionSelection(request.baseCandidateId), TownActionSelection(request.materialCandidateId)),
                ) to mapOf("amount" to request.quantity.toString())
            },
        ) { html, finalUrl, result, page -> CardChangeResponse.from(parser.parseChange(html, finalUrl, page, result).copy(selectedBaseCandidateId = request.baseCandidateId)) }
    }

    fun loadSell(accountId: Long): CardSellResponse = executor.loadProjected(accountId, url(TownFeatureId.CARD_SELL)) { html, finalUrl, page ->
        CardSellResponse.from(parser.parseSell(html, finalUrl, page))
    }

    fun sell(accountId: Long, request: CardSellRequest): CardSellResponse {
        if (request.cards.map { it.candidateId }.distinct().size != request.cards.size) invalid("같은 카드를 중복 선택할 수 없습니다.")
        return executor.executeProjected(accountId, url(TownFeatureId.CARD_SELL), resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parseSell(html, finalUrl, page)
            request.cards.forEach { line ->
                val card = snapshot.cards.singleOrNull { it.id == line.candidateId && it.selectable } ?: invalid("현재 판매할 수 없는 카드입니다.")
                if (line.quantity > (card.maxQuantity ?: Int.MAX_VALUE)) invalid("보유 수량보다 많이 판매할 수 없습니다.")
            }
            TownActionRequest(requireAction(snapshot.actionId), request.cards.map { TownActionSelection(it.candidateId, it.quantity) })
        }) { html, finalUrl, result, page -> CardSellResponse.from(parser.parseSell(html, finalUrl, page, result)) }
    }

    fun loadSoulEcho(accountId: Long): SoulEchoResponse {
        val pageUrl = resolveSoulEcho(accountId)
        return executor.loadProjected(accountId, pageUrl) { html, finalUrl, page -> SoulEchoResponse.from(parser.parseSoulEcho(html, finalUrl, page)) }
    }

    fun fuseSoulEcho(accountId: Long, request: SoulEchoFuseRequest): SoulEchoResponse {
        val pageUrl = resolveSoulEcho(accountId)
        return executor.executeProjected(accountId, pageUrl, resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parseSoulEcho(html, finalUrl, page)
            val recipe = snapshot.recipes.singleOrNull { it.id == request.recipeCandidateId && it.selectable }
                ?: invalid("현재 융합할 수 없는 소울 에코 품목입니다.")
            if (snapshot.currentCategoryId != request.categoryCandidateId || recipe.category != request.categoryCandidateId) {
                invalid("현재 표시된 품목 분류의 소울 에코만 융합할 수 있습니다.")
            }
            TownActionRequest(requireAction(snapshot.actionId), listOf(TownActionSelection(request.categoryCandidateId), TownActionSelection(request.recipeCandidateId)))
        }) { html, finalUrl, result, page -> SoulEchoResponse.from(parser.parseSoulEcho(html, finalUrl, page, result)) }
    }

    private fun url(feature: TownFeatureId) = locations.resolve(feature).url
    private fun resolveSoulEcho(accountId: Long): String = try { locations.resolve(TownFeatureId.SOUL_ECHO).url } catch (error: ApiException) {
        if (error.errorCode != ErrorCode.RESOURCE_NOT_FOUND) throw error
        executor.loadProjected(accountId, TOWN_URL) { html, _, _ -> locations.resolve(TownFeatureId.SOUL_ECHO, html).url }
    }
    private fun requireAction(actionId: String?) = actionId ?: invalid("현재 HOF 카드 작업 양식을 찾지 못했습니다.")
    private fun loadOptions(accountId: Long, feature: TownFeatureId, baseId: String): CardUpgradeResponse {
        val pageUrl = url(feature)
        return executor.loadProjected(accountId, pageUrl) { html, finalUrl, page ->
            val snapshot = parser.parseUpgrade(html, finalUrl, page)
            requireCombinedStage(snapshot.baseCards, snapshot.materialCards)
            validateBase(snapshot.baseCards, baseId)
            CardUpgradeResponse.from(snapshot.copy(selectedBaseCandidateId = baseId))
        }
    }
    private fun loadChangeOptions(accountId: Long, feature: TownFeatureId, baseId: String): CardChangeResponse {
        val pageUrl = url(feature)
        return executor.loadProjected(accountId, pageUrl) { html, finalUrl, page ->
            val snapshot = parser.parseChange(html, finalUrl, page)
            requireCombinedStage(snapshot.baseCards, snapshot.materialCards)
            validateBase(snapshot.baseCards, baseId)
            CardChangeResponse.from(snapshot.copy(selectedBaseCandidateId = baseId))
        }
    }
    private fun distinctCards(base: String, material: String) { if (base == material) invalid("베이스 카드와 추가 카드는 서로 달라야 합니다.") }
    private fun requireCombinedStage(base: List<app.spammy.hof.town.card.model.CardCandidate>, material: List<app.spammy.hof.town.card.model.CardCandidate>) {
        if (base.none { it.selectable } || material.none { it.selectable }) invalid("현재 HOF 카드 양식이 변경되었습니다.")
    }
    private fun validateBase(cards: List<app.spammy.hof.town.card.model.CardCandidate>, id: String) =
        cards.singleOrNull { it.id == id && it.selectable } ?: invalid("현재 카드 목록에서 베이스 카드를 다시 선택해 주세요.")
    private fun validateMaterial(cards: List<app.spammy.hof.town.card.model.CardCandidate>, id: String) =
        cards.singleOrNull { it.id == id && it.selectable } ?: invalid("현재 카드 목록에서 추가 카드를 다시 선택해 주세요.")
    private fun selectedMax(max: Int, baseOwned: Int?, materialOwned: Int?) = minOf(max, baseOwned ?: max, materialOwned ?: max)
    private fun validateQuantity(quantity: Int, min: Int, max: Int) { if (quantity !in min..max) invalid("수량은 $min~$max 사이여야 합니다.") }
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private companion object { const val TOWN_URL = "https://hof.zerosic.com/index.php?menu=town" }
}
