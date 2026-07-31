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

    fun upgrade(accountId: Long, request: CardUpgradeRequest): CardUpgradeResponse {
        distinctCards(request.baseCandidateId, request.materialCandidateId)
        val pageUrl = url(TownFeatureId.CARD_UPGRADE)
        return executor.executeProjectedWithScalars(
            accountId, pageUrl,
            resolveAction = { html, finalUrl, page ->
                val snapshot = parser.parseUpgrade(html, finalUrl, page)
                val pair = validatePair(snapshot.baseCards, snapshot.materialCards, request.baseCandidateId, request.materialCandidateId)
                if (pair.first.sourceKey == pair.second.sourceKey) invalid("베이스 카드와 추가 카드는 서로 달라야 합니다.")
                validateQuantity(request.quantity, snapshot.minQuantity, minOf(snapshot.maxQuantity, pair.second.owned ?: snapshot.maxQuantity))
                TownActionRequest(requireAction(snapshot.actionId), listOf(TownActionSelection(request.baseCandidateId), TownActionSelection(request.materialCandidateId)))
            },
            scalarValues = mapOf("amount" to request.quantity.toString()), requiredScalarFields = setOf("amount"), requiredSubmitField = "Create",
        ) { html, finalUrl, result, page -> CardUpgradeResponse.from(parser.parseUpgrade(html, finalUrl, page, result)) }
    }

    fun loadChange(accountId: Long): CardChangeResponse = executor.loadProjected(accountId, url(TownFeatureId.CARD_CHANGE)) { html, finalUrl, page ->
        CardChangeResponse.from(parser.parseChange(html, finalUrl, page))
    }

    fun change(accountId: Long, request: CardChangeRequest): CardChangeResponse {
        distinctCards(request.baseCandidateId, request.materialCandidateId)
        val pageUrl = url(TownFeatureId.CARD_CHANGE)
        return executor.executeProjectedWithScalars(
            accountId, pageUrl,
            resolveAction = { html, finalUrl, page ->
                val snapshot = parser.parseChange(html, finalUrl, page)
                val pair = validatePair(snapshot.baseCards, snapshot.materialCards, request.baseCandidateId, request.materialCandidateId)
                if (pair.first.sourceKey == pair.second.sourceKey) invalid("베이스 카드와 변화 재료는 서로 달라야 합니다.")
                validateQuantity(request.quantity, snapshot.minQuantity, minOf(snapshot.maxQuantity, pair.second.owned ?: snapshot.maxQuantity))
                TownActionRequest(requireAction(snapshot.actionId), listOf(TownActionSelection(request.baseCandidateId), TownActionSelection(request.materialCandidateId)))
            },
            scalarValues = mapOf("amount" to request.quantity.toString()), requiredScalarFields = setOf("amount"), requiredSubmitField = "Create",
        ) { html, finalUrl, result, page -> CardChangeResponse.from(parser.parseChange(html, finalUrl, page, result)) }
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
            if (snapshot.recipes.none { it.id == request.recipeCandidateId && it.selectable }) invalid("현재 융합할 수 없는 소울 에코 품목입니다.")
            if (snapshot.categories.none { it.id == request.categoryCandidateId }) invalid("현재 선택할 수 없는 품목 분류입니다.")
            TownActionRequest(requireAction(snapshot.actionId), listOf(TownActionSelection(request.categoryCandidateId), TownActionSelection(request.recipeCandidateId)))
        }) { html, finalUrl, result, page -> SoulEchoResponse.from(parser.parseSoulEcho(html, finalUrl, page, result)) }
    }

    private fun url(feature: TownFeatureId) = locations.resolve(feature).url
    private fun resolveSoulEcho(accountId: Long): String = try { locations.resolve(TownFeatureId.SOUL_ECHO).url } catch (error: ApiException) {
        if (error.errorCode != ErrorCode.RESOURCE_NOT_FOUND) throw error
        executor.loadProjected(accountId, TOWN_URL) { html, _, _ -> locations.resolve(TownFeatureId.SOUL_ECHO, html).url }
    }
    private fun requireAction(actionId: String?) = actionId ?: invalid("현재 HOF 카드 작업 양식을 찾지 못했습니다.")
    private fun distinctCards(base: String, material: String) { if (base == material) invalid("베이스 카드와 추가 카드는 서로 달라야 합니다.") }
    private fun validatePair(baseCards: List<app.spammy.hof.town.card.model.CardCandidate>, materialCards: List<app.spammy.hof.town.card.model.CardCandidate>, base: String, material: String): Pair<app.spammy.hof.town.card.model.CardCandidate, app.spammy.hof.town.card.model.CardCandidate> {
        val baseCard = baseCards.singleOrNull { it.id == base && it.selectable }
        val materialCard = materialCards.singleOrNull { it.id == material && it.selectable }
        if (baseCard == null || materialCard == null) invalid("현재 카드 목록에서 베이스와 추가 카드를 다시 선택해 주세요.")
        return baseCard to materialCard
    }
    private fun validateQuantity(quantity: Int, min: Int, max: Int) { if (quantity !in min..max) invalid("수량은 $min~$max 사이여야 합니다.") }
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private companion object { const val TOWN_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=town" }
}
