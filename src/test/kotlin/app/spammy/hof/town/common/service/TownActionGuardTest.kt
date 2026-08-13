package app.spammy.hof.town.common.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownEditableField
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownRow
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFieldValue
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
        hiddenFields = listOf(HofFormField("csrf", "fresh-token")),
        submitFields = listOf(HofFormField("Create", "교환")),
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

    @Test
    fun `rejects a requested quantity when the fresh form has no quantity control`() {
        val withoutQuantity = candidate.copy(quantityFieldName = null)
        val current = page.copy(forms = listOf(form.copy(rows = listOf(ParsedTownRow("item", withoutQuantity)))))

        assertFailsWith<ApiException> {
            guard.guard(current, TownActionRequest("action-1", listOf(TownActionSelection("item-1", 2))))
        }
    }

    @Test
    fun `submits only freshly observed editable fields and enforces their contract`() {
        val current = page.copy(forms = listOf(form.copy(editableFields = listOf(
            ParsedTownEditableField(
                id = "name-field",
                label = "새 이름",
                inputName = "newname",
                maxLength = 16,
                inputPosition = 1,
            ),
        ))))

        val guarded = guard.guard(
            current,
            TownActionRequest("action-1", values = listOf(TownFieldValue("name-field", "새이름"))),
        )
        assertEquals("새이름", guarded.formFields["newname"])
        assertFailsWith<ApiException> {
            guard.guard(current, TownActionRequest("action-1", values = listOf(TownFieldValue("unknown", "x"))))
        }
        assertFailsWith<ApiException> {
            guard.guard(current, TownActionRequest("action-1", values = listOf(TownFieldValue("name-field", "12345678901234567"))))
        }
    }
}
