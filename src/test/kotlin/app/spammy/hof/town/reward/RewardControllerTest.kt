package app.spammy.hof.town.reward

import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.reward.dto.StashOpenRequest
import app.spammy.hof.town.reward.model.OrbExchangeAction
import app.spammy.hof.town.reward.model.StashOpenAction
import app.spammy.hof.town.reward.parser.OrbExchangeParser
import app.spammy.hof.town.reward.parser.StashPageParser
import jakarta.validation.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewardControllerTest {
    private val forms = HofFormParser()
    private val guard = TownActionGuard()
    private val stash = StashPageParser()
    private val orbs = OrbExchangeParser()
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test fun `stash request rejects a blank candidate and uses latest opaque action form`() {
        assertTrue(validator.validate(StashOpenRequest("", StashOpenAction.ONE)).isNotEmpty())
        val html = fixture("stash.html")
        val page = forms.parse(html, STASH_URL)
        val snapshot = stash.parse(html, STASH_URL, page)
        val action = snapshot.actions.single { it.action == StashOpenAction.THOUSAND }
        val box = snapshot.boxes.single { it.selectable }

        val guarded = guard.guard(page, TownActionRequest(action.actionId, listOf(TownActionSelection(box.id))))
        assertEquals(listOf("ItemNo", "future-action"), guarded.formEntries.map { it.name })
        assertEquals("1000개 열기", guarded.formEntries.last().value)
    }

    @Test fun `orb actions submit only the confirmed HOF one or five button`() {
        val html = fixture("orbs-before.html")
        val page = forms.parse(html, ORB_URL)
        val snapshot = orbs.parse(html, ORB_URL, page)
        val one = snapshot.actions.single { it.action == OrbExchangeAction.ONE }
        val five = snapshot.actions.single { it.action == OrbExchangeAction.FIVE }

        assertEquals(listOf("TestBTN2"), guard.guard(page, TownActionRequest(one.actionId)).formEntries.map { it.name })
        assertEquals(listOf("TestBTN3"), guard.guard(page, TownActionRequest(five.actionId)).formEntries.map { it.name })
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/reward/$name")).readText()
    private companion object {
        const val STASH_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=stash"
        const val ORB_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=orbboxshop"
    }
}
