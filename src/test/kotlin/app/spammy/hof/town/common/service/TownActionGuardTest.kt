package app.spammy.hof.town.common.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownRow
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TownActionGuardTest {
    private val guard = TownActionGuard()
    private val candidate = ParsedTownCandidate(
        id = "item-1",
        label = "선택 가능 아이템",
        inputName = "item",
        inputValue = "item-1",
        quantityFieldName = "quantity",
        maxQuantity = 10,
    )
    private val form = ParsedTownForm(
        actionId = "action-1",
        method = HofHttpMethod.POST,
        actionUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=shop",
        rows = listOf(ParsedTownRow("선택 가능 아이템", candidate)),
        hiddenFields = mapOf("csrf" to "fresh-token"),
        submitFields = mapOf("Create" to "교환"),
    )
    private val page = ParsedTownPage(listOf(form))

    @Test
    fun `builds fields exclusively from fresh parsed form`() {
        val guarded = guard.guard(
            page,
            TownActionRequest("action-1", listOf(TownActionSelection("item-1", 3))),
        )

        assertEquals(
            mapOf("csrf" to "fresh-token", "Create" to "교환", "item" to "item-1", "quantity" to "3"),
            guarded.formFields,
        )
    }

    @Test
    fun `rejects stale action unknown candidate and invalid quantity`() {
        assertFailsWith<ApiException> {
            guard.guard(page, TownActionRequest("stale", listOf(TownActionSelection("item-1", 1))))
        }
        assertFailsWith<ApiException> {
            guard.guard(page, TownActionRequest("action-1", listOf(TownActionSelection("foreign", 1))))
        }
        assertFailsWith<ApiException> {
            guard.guard(page, TownActionRequest("action-1", listOf(TownActionSelection("item-1", 11))))
        }
    }
}
