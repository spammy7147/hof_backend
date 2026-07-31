package app.spammy.hof.town.card

import app.spammy.hof.town.card.dto.CardChangeRequest
import app.spammy.hof.town.card.dto.CardIdentifyRequest
import app.spammy.hof.town.card.dto.CardSellLineRequest
import app.spammy.hof.town.card.dto.CardSellRequest
import app.spammy.hof.town.card.dto.CardUpgradeRequest
import app.spammy.hof.town.card.dto.SoulEchoFuseRequest
import app.spammy.hof.town.card.parser.CardPageParser
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.service.TownActionGuard
import jakarta.validation.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CardControllerTest {
    private val forms = HofFormParser()
    private val parser = CardPageParser()
    private val guard = TownActionGuard()
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test fun `typed requests keep five card actions separate and enforce quantity bounds`() {
        assertTrue(validator.validate(CardIdentifyRequest("")).isNotEmpty())
        assertTrue(validator.validate(CardUpgradeRequest("base", "material", 0)).isNotEmpty())
        assertTrue(validator.validate(CardChangeRequest("base", "material", 11)).isNotEmpty())
        assertTrue(validator.validate(CardSellRequest(emptyList())).isNotEmpty())
        assertTrue(validator.validate(SoulEchoFuseRequest("recipe", "")).isNotEmpty())
        assertTrue(validator.validate(CardSellRequest(listOf(CardSellLineRequest("card", 1)))).isEmpty())
    }

    @Test fun `upgrade submits actual ItemNo AddMaterial amount and Create controls only`() {
        val html = fixture("upgrade.html")
        val page = forms.parse(html, URL)
        val snapshot = parser.parseUpgrade(html, URL, page)
        val guarded = guard.guard(page, TownActionRequest(snapshot.actionId!!, listOf(
            TownActionSelection(snapshot.baseCards.first { it.selectable }.id),
            TownActionSelection(snapshot.materialCards.first { it.selectable }.id),
        )))
        assertEquals(listOf("ItemNo", "AddMaterial", "Create"), guarded.formEntries.map { it.name })
        assertFalse(guarded.formEntries.any { it.name == "amount" }, "amount는 scalar allowlist에서만 추가한다")
    }

    @Test fun `sell preserves each check amount pair and one ItemSell submit`() {
        val html = fixture("sell.html")
        val page = forms.parse(html, URL)
        val snapshot = parser.parseSell(html, URL, page)
        val guarded = guard.guard(page, TownActionRequest(snapshot.actionId!!, snapshot.cards.map { TownActionSelection(it.id, 2.coerceAtMost(it.maxQuantity ?: 1)) }))
        assertEquals(listOf("check_401", "amount_401", "check_402", "amount_402", "ItemSell"), guarded.formEntries.map { it.name })
        assertEquals(1, guarded.formEntries.count { it.name == "ItemSell" })
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/card/$name")).readText()
    private companion object { const val URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=cardmix" }
}
