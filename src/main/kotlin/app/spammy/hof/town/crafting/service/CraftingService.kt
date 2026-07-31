package app.spammy.hof.town.crafting.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.crafting.dto.*
import app.spammy.hof.town.crafting.model.*
import app.spammy.hof.town.crafting.parser.CraftingPageParser
import org.springframework.stereotype.Service

@Service
class CraftingService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: CraftingPageParser,
) {
    fun load(accountId: Long, mode: CraftingMode): CraftingResponse = executor.loadProjected(accountId, url(mode)) { html, finalUrl, page ->
        CraftingResponse.from(parser.parse(mode, html, finalUrl, page))
    }

    fun loadCategory(accountId: Long, mode: CraftingMode, categoryCandidateId: String): CraftingResponse =
        executor.loadSelectedOptionProjected(
            accountId = accountId,
            pageUrl = url(mode),
            actionId = { html, finalUrl, page ->
                parser.parse(mode, html, finalUrl, page).actionId
                    ?: invalid("현재 HOF 품목 분류 양식을 찾지 못했습니다.")
            },
            optionCandidateId = categoryCandidateId,
            requiredOptionField = categoryField(mode),
            requiredFormSubmitField = submitField(mode),
            excludedActionFields = categoryTransitionActionFields(mode),
        ) { html, finalUrl, page ->
            val snapshot = parser.parse(mode, html, finalUrl, page)
            if (snapshot.currentCategoryId != categoryCandidateId) {
                invalid("HOF가 요청한 품목 분류로 전환하지 않았습니다. 목록을 새로고침해 주세요.")
            }
            CraftingResponse.from(snapshot)
        }

    fun startWorkbase(accountId: Long, request: WorkbaseStartRequest): CraftingResponse = executeItemT(
        accountId, CraftingMode.WORKBASE, request.candidateId, request.categoryCandidateId, request.quantity,
    )

    fun completeWorkbase(accountId: Long): CraftingResponse = executor.executeProjected(
        accountId = accountId,
        pageUrl = url(CraftingMode.WORKBASE),
        resolveAction = { html, finalUrl, page ->
            val snapshot = parser.parse(CraftingMode.WORKBASE, html, finalUrl, page)
            val actionId = snapshot.completionActionId ?: invalid("현재 수령할 수 있는 작업장 제작 결과가 없습니다.")
            TownActionRequest(actionId)
        },
    ) { html, finalUrl, result, page -> CraftingResponse.from(parser.parse(CraftingMode.WORKBASE, html, finalUrl, page, result)) }

    fun craftClaris(accountId: Long, request: ClarisCraftRequest): CraftingResponse = executeItemT(
        accountId, CraftingMode.CLARIS, request.candidateId, request.categoryCandidateId, request.quantity,
    )

    fun refine(accountId: Long, mode: CraftingMode, request: RefineRequest): CraftingResponse {
        require(mode == CraftingMode.REFINE || mode == CraftingMode.VETERAN)
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url(mode),
            resolveAction = { html, finalUrl, page ->
                val snapshot = parser.parse(mode, html, finalUrl, page)
                validateCategory(snapshot, request.categoryCandidateId)
                validateRow(snapshot, request.candidateId)
                val countId = snapshot.refineCountCandidateIds[request.refineCount]
                    ?: invalid("현재 HOF가 허용하는 제련 횟수를 다시 선택해 주세요.")
                val selections = mutableListOf(
                    TownActionSelection(request.candidateId),
                    TownActionSelection(request.categoryCandidateId),
                    TownActionSelection(countId),
                )
                if (mode == CraftingMode.VETERAN) {
                    selections += TownActionSelection(snapshot.timesADefaultCandidateId
                        ?: invalid("현재 장로대장간의 기본 제련 방식을 안전하게 확인하지 못했습니다."))
                }
                TownActionRequest(snapshot.actionId ?: invalid("현재 HOF 제련 양식을 찾지 못했습니다."), selections)
            },
        ) { html, finalUrl, result, page -> CraftingResponse.from(parser.parse(mode, html, finalUrl, page, result)) }
    }

    fun create(accountId: Long, request: CreateCraftRequest): CraftingResponse {
        val mode = CraftingMode.CREATE
        val pageUrl = url(mode)
        return executor.executeResolvedProjectedWithScalars(
            accountId = accountId,
            pageUrl = pageUrl,
            resolve = { html, finalUrl, page ->
                val snapshot = parser.parse(mode, html, finalUrl, page)
                validateCategory(snapshot, request.categoryCandidateId)
                val recipe = validateRow(snapshot, request.recipeCandidateId)
                validateQuantity(request.quantity, snapshot)
                val selections = mutableListOf(
                    TownActionSelection(recipe.id),
                    TownActionSelection(request.categoryCandidateId),
                )
                request.additionalMaterialCandidateId?.let { id ->
                    if (snapshot.additionalMaterials.none { it.id == id && it.selectable }) invalid("현재 사용할 수 없는 추가 소재입니다.")
                    selections += TownActionSelection(id)
                }
                TownActionRequest(snapshot.actionId ?: invalid("현재 HOF 제작 양식을 찾지 못했습니다."), selections) to
                    mapOf("ItemT" to (recipe.itemT ?: invalid("현재 품목의 HOF ItemT 계약을 안전하게 확인하지 못했습니다.")), "amount" to request.quantity.toString())
            },
            requiredScalarFields = setOf("ItemT", "amount"),
            requiredSubmitField = "Create",
        ) { html, finalUrl, result, page ->
            CraftingResponse.from(parser.parse(
                mode, html, finalUrl, page, result,
                warningCode = if (request.additionalMaterialCandidateId == null) NO_ADDITIONAL_MATERIAL else null,
            ))
        }
    }

    /** scalar는 executor 최신 GET에서 다시 검증되지만 값도 별도 GET이 아니라 opaque candidate의 관측값만 사용한다. */
    private fun executeItemT(
        accountId: Long,
        mode: CraftingMode,
        candidateId: String,
        categoryCandidateId: String,
        quantity: Int,
    ): CraftingResponse {
        return executor.executeResolvedProjectedWithScalars(
            accountId = accountId,
            pageUrl = url(mode),
            resolve = { html, finalUrl, page ->
                val snapshot = parser.parse(mode, html, finalUrl, page)
                validateCategory(snapshot, categoryCandidateId)
                val row = validateRow(snapshot, candidateId)
                validateQuantity(quantity, snapshot)
                TownActionRequest(
                    snapshot.actionId ?: invalid("현재 HOF 제작 양식을 찾지 못했습니다."),
                    listOf(TownActionSelection(candidateId), TownActionSelection(categoryCandidateId)),
                ) to mapOf(
                    "ItemT" to (row.itemT ?: invalid("현재 품목의 HOF ItemT 계약을 안전하게 확인하지 못했습니다.")),
                    "amount" to quantity.toString(),
                )
            },
            requiredScalarFields = setOf("ItemT", "amount"),
            requiredSubmitField = "Create",
        ) { html, finalUrl, result, page -> CraftingResponse.from(parser.parse(mode, html, finalUrl, page, result)) }
    }

    private fun validateRow(snapshot: CraftingSnapshot, candidateId: String): CraftingRow =
        snapshot.rows.singleOrNull { it.id == candidateId && it.selectable }
            ?: invalid("현재 선택할 수 없는 품목입니다. 목록을 갱신해 주세요.")

    private fun validateCategory(snapshot: CraftingSnapshot, categoryCandidateId: String) {
        if (snapshot.categoryCandidateId != categoryCandidateId) invalid("현재 표시된 품목 분류를 다시 확인해 주세요.")
    }

    private fun validateQuantity(quantity: Int, snapshot: CraftingSnapshot) {
        if (quantity !in snapshot.minQuantity..snapshot.maxQuantity) {
            invalid("수량은 ${snapshot.minQuantity}~${snapshot.maxQuantity} 사이여야 합니다.")
        }
    }

    private fun url(mode: CraftingMode) = locations.resolve(when (mode) {
        CraftingMode.WORKBASE -> TownFeatureId.WORKBASE
        CraftingMode.CLARIS -> TownFeatureId.SEWING_SHOP
        CraftingMode.REFINE -> TownFeatureId.REFINE_WORKSHOP
        CraftingMode.CREATE -> TownFeatureId.CREATE_WORKSHOP
        CraftingMode.VETERAN -> TownFeatureId.VETERAN_SMITHY
    }).url

    private fun categoryField(mode: CraftingMode) = when (mode) {
        CraftingMode.WORKBASE, CraftingMode.CLARIS, CraftingMode.CREATE -> "type_create"
        CraftingMode.REFINE, CraftingMode.VETERAN -> "type"
    }

    private fun submitField(mode: CraftingMode) = when (mode) {
        CraftingMode.WORKBASE, CraftingMode.CLARIS, CraftingMode.CREATE -> "Create"
        CraftingMode.REFINE, CraftingMode.VETERAN -> "refine"
    }

    private fun categoryTransitionActionFields(mode: CraftingMode) = when (mode) {
        CraftingMode.WORKBASE, CraftingMode.CLARIS, CraftingMode.CREATE ->
            setOf("Create", "ItemNo", "ItemT", "amount", "AddMaterial")
        CraftingMode.REFINE, CraftingMode.VETERAN ->
            setOf("refine", "item_no", "timesA", "timesB")
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
