package app.spammy.hof.town.exchange.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.exchange.dto.*
import app.spammy.hof.town.exchange.model.*
import app.spammy.hof.town.exchange.parser.ExchangePageParser
import app.spammy.hof.town.exchange.parser.ExchangeCategoryException
import app.spammy.hof.town.exchange.parser.ExchangeContractException
import org.springframework.stereotype.Service

@Service
class ExchangeService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: ExchangePageParser,
) {
    fun load(accountId: Long, mode: ExchangeMode): ExchangeResponse = executor.loadProjected(accountId, url(mode)) { html, finalUrl, page ->
        ExchangeResponse.from(parse(mode, html, finalUrl, page))
    }

    fun loadCategory(accountId: Long, mode: ExchangeMode, candidateId: String): ExchangeResponse {
        if (mode == ExchangeMode.ANN) invalid("앤의 가게에는 품목 분류가 없습니다.")
        if (mode in STATIC_CATALOG_MODES) return executor.loadProjected(accountId, url(mode)) { html, finalUrl, page ->
            val snapshot = parse(mode, html, finalUrl, page, categoryCandidateId = candidateId)
            if (snapshot.currentCategoryId != candidateId) invalid("HOF가 요청한 분류로 전환하지 않았습니다.")
            ExchangeResponse.from(snapshot)
        }
        return executor.loadResolvedSelectedOptionProjected(
            accountId = accountId,
            pageUrl = url(mode),
            optionCandidateId = candidateId,
            excludedActionFields = setOf("Create", "Trade", "ItemNo", "item_no", "ItemT", "list_type", "amount", "suu"),
            resolveContract = { html, finalUrl, page ->
                val snapshot = parser.parse(mode, html, finalUrl, page)
                Triple(
                    snapshot.tradeActionId ?: invalid("현재 교환 양식을 찾지 못했습니다."),
                    snapshot.categoryField ?: invalid("현재 교환 분류 선택란을 하나로 확인하지 못했습니다."),
                    tradeSubmit(mode),
                )
            },
        ) { html, finalUrl, page ->
            val snapshot = parser.parse(mode, html, finalUrl, page)
            if (snapshot.currentCategoryId != candidateId) invalid("HOF가 요청한 분류로 전환하지 않았습니다.")
            ExchangeResponse.from(snapshot)
        }
    }

    fun trade(accountId: Long, mode: ExchangeMode, request: ExchangeTradeRequest): ExchangeResponse {
        if (mode == ExchangeMode.ANN) invalid("앤의 가게 action을 선택해 주세요.")
        if (mode in STATIC_CATALOG_MODES) return tradeStaticCatalog(accountId, mode, request)
        return executor.executeResolvedProjectedWithScalars(
            accountId = accountId,
            pageUrl = url(mode),
            requiredScalarFields = setOf("ItemT", "list_type", "amount"),
            requiredSubmitField = tradeSubmit(mode),
            resolve = { html, finalUrl, page ->
                val snapshot = parser.parse(mode, html, finalUrl, page)
                validateCategory(snapshot, request.categoryCandidateId)
                val row = snapshot.rows.singleOrNull { it.id == request.candidateId && it.selectable }
                    ?: invalid("현재 선택할 수 없는 교환 품목입니다.")
                validateQuantity(request.quantity, row)
                val category = snapshot.categories.singleOrNull { it.id == snapshot.currentCategoryId }
                    ?: invalid("현재 교환 분류를 하나로 확인하지 못했습니다.")
                val categoryCandidate = page.forms.singleOrNull { it.actionId == snapshot.tradeActionId }
                    ?.candidates?.singleOrNull { it.id == category.id }
                    ?: invalid("현재 교환 분류 계약을 확인하지 못했습니다.")
                TownActionRequest(
                    snapshot.tradeActionId ?: invalid("현재 교환 양식을 찾지 못했습니다."),
                    listOf(TownActionSelection(row.id)),
                ) to mapOf(
                    "ItemT" to (row.itemT ?: invalid("현재 품목의 HOF ItemT 계약을 안전하게 확인하지 못했습니다.")),
                    "list_type" to categoryCandidate.inputValue,
                    "amount" to request.quantity.toString(),
                )
            },
        ) { html, finalUrl, result, page -> ExchangeResponse.from(parser.parse(mode, html, finalUrl, page, result)) }
    }

    private fun tradeStaticCatalog(accountId: Long, mode: ExchangeMode, request: ExchangeTradeRequest): ExchangeResponse {
        val categoryCandidateId = request.categoryCandidateId ?: invalid("현재 교환 분류를 선택해 주세요.")
        return executor.executeMaterializedResolvedProjectedWithScalars(
            accountId = accountId,
            pageUrl = url(mode),
            requiredScalarFields = setOf("ItemT", "amount"),
            requiredSubmitField = tradeSubmit(mode),
            requiredSyntheticFields = setOf("list_type"),
            requiredReplacedFields = setOf("ItemNo"),
            materialize = { html, finalUrl -> materialize(html, finalUrl, categoryCandidateId) },
            resolve = { html, finalUrl, page ->
                val snapshot = parse(mode, html, finalUrl, page, categoryCandidateId = categoryCandidateId)
                validateCategory(snapshot, categoryCandidateId)
                val row = snapshot.rows.singleOrNull { it.id == request.candidateId && it.selectable }
                    ?: invalid("현재 선택할 수 없는 교환 품목입니다.")
                validateQuantity(request.quantity, row)
                TownActionRequest(
                    snapshot.tradeActionId ?: invalid("현재 교환 양식을 찾지 못했습니다."),
                    listOf(TownActionSelection(row.id)),
                ) to mapOf(
                    "ItemT" to (row.itemT ?: invalid("현재 품목의 HOF ItemT 계약을 안전하게 확인하지 못했습니다.")),
                    "amount" to request.quantity.toString(),
                )
            },
        ) { html, finalUrl, result, page ->
            ExchangeResponse.from(parse(mode, html, finalUrl, page, result, categoryCandidateId))
        }
    }

    fun exchangeLegacyGrade(accountId: Long, request: LegacyGradeExchangeRequest): ExchangeResponse = executor.executeProjected(
        accountId = accountId,
        pageUrl = url(ExchangeMode.LEGACY),
        resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parse(ExchangeMode.LEGACY, html, finalUrl, page)
            val action = snapshot.gradeActions.singleOrNull { it.id == request.gradeActionId }
                ?: invalid("현재 HOF에서 제공하지 않는 등급 교환입니다.")
            if (action.allowsTargetSelection || action.consumedItemsPerPress != 1) invalid("유물 등급 교환 계약이 변경되었습니다.")
            TownActionRequest(action.id)
        },
    ) { html, finalUrl, result, page -> ExchangeResponse.from(parser.parse(ExchangeMode.LEGACY, html, finalUrl, page, result)) }

    fun annAction(accountId: Long, request: AnnActionRequest): ExchangeResponse = executor.executeProjected(
        accountId = accountId,
        pageUrl = url(ExchangeMode.ANN),
        resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parse(ExchangeMode.ANN, html, finalUrl, page)
            val group = snapshot.annActions.singleOrNull { it.type == request.action }
                ?: invalid("현재 앤의 가게에서 제공하지 않는 action입니다.")
            val selections = if (group.rows.any(ExchangeRow::selectable)) {
                val id = request.candidateId ?: invalid("대상을 선택해 주세요.")
                val row = group.rows.singleOrNull { it.id == id && it.selectable } ?: invalid("현재 선택할 수 없는 대상입니다.")
                validateQuantity(request.quantity, row)
                listOf(TownActionSelection(id, request.quantity))
            } else {
                if (request.candidateId != null || request.quantity != 1) invalid("이 action은 대상을 받지 않습니다.")
                emptyList()
            }
            TownActionRequest(group.actionId, selections)
        },
    ) { html, finalUrl, result, page -> ExchangeResponse.from(parser.parse(ExchangeMode.ANN, html, finalUrl, page, result)) }

    private fun validateCategory(snapshot: ExchangeSnapshot, requested: String?) {
        val current = snapshot.currentCategoryId
        if (current != null && requested != current) invalid("현재 표시된 교환 분류를 다시 확인해 주세요.")
        if (current == null && requested != null) invalid("현재 교환 분류가 없습니다.")
    }

    private fun validateQuantity(quantity: Int, row: ExchangeRow) {
        val max = row.maxQuantity ?: Int.MAX_VALUE
        if (quantity !in row.minQuantity..max) invalid("현재 HOF가 허용하는 수량을 입력해 주세요.")
    }

    private fun url(mode: ExchangeMode) = locations.resolve(when (mode) {
        ExchangeMode.EMBLEM -> TownFeatureId.EMBLEM_SHOP
        ExchangeMode.EVENT -> TownFeatureId.EVENT_SHOP
        ExchangeMode.LEGACY -> TownFeatureId.LEGACY_SHOP
        ExchangeMode.ANN -> TownFeatureId.ANN_SHOP
    }).url

    private fun tradeSubmit(mode: ExchangeMode) = if (mode == ExchangeMode.LEGACY) "Trade" else "Create"
    private fun parse(
        mode: ExchangeMode,
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
        categoryCandidateId: String? = null,
    ) = try {
        parser.parse(mode, html, finalUrl, page, result, categoryCandidateId)
    } catch (_: ExchangeCategoryException) {
        invalid("현재 HOF에서 선택할 수 없는 교환 분류입니다.")
    } catch (_: ExchangeContractException) {
        throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "현재 교환상점 응답 형식을 확인할 수 없습니다.")
    }

    private fun materialize(html: String, finalUrl: String, categoryCandidateId: String): String = try {
        parser.materializeStaticCatalogHtml(html, finalUrl, categoryCandidateId)
    } catch (_: ExchangeCategoryException) {
        invalid("현재 HOF에서 선택할 수 없는 교환 분류입니다.")
    } catch (_: ExchangeContractException) {
        throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "현재 교환상점 응답 형식을 확인할 수 없습니다.")
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private companion object {
        val STATIC_CATALOG_MODES = setOf(ExchangeMode.EMBLEM, ExchangeMode.EVENT)
    }
}
