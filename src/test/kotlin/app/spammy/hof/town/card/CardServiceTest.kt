package app.spammy.hof.town.card

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.card.dto.CardBaseOptionsRequest
import app.spammy.hof.town.card.dto.CardChangeRequest
import app.spammy.hof.town.card.dto.CardUpgradeRequest
import app.spammy.hof.town.card.parser.CardPageParser
import app.spammy.hof.town.card.service.CardService
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.common.service.ResolvedTownLocation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito

class CardServiceTest {
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val locations = Mockito.mock(TownLocationResolver::class.java)
    private val service = CardService(
        TownAuthenticatedExecutor(accounts, cookies, HofRequestFactory(), gateway, LoginStateParser(), HofFormParser(), HofResultParser(), TownActionGuard(), app.spammy.hof.town.common.service.AccountHofMutationFence()),
        locations,
        CardPageParser(),
    )

    @Test fun `live single stage upgrade options return materials without posting the destructive create form`() {
        stub(TownFeatureId.CARD_UPGRADE, UPGRADE_URL, LIVE_UPGRADE)

        val response = service.loadUpgradeOptions(7L, CardBaseOptionsRequest(baseId()))

        assertEquals(baseId(), response.selectedBaseCandidateId)
        assertEquals(listOf("Material Card x3 / ★★"), response.materialCards.map { it.label })
        assertEquals(listOf(HofHttpMethod.GET), captureRequests().map(HofRequest::method))
    }

    @Test fun `live single stage upgrade posts base material amount and create exactly once`() {
        stub(TownFeatureId.CARD_UPGRADE, UPGRADE_URL, LIVE_UPGRADE, LIVE_UPGRADE)

        service.upgrade(7L, CardUpgradeRequest(baseId(), materialId(), 2))

        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("ItemNo", "amount", "AddMaterial", "Create"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("201", "2", "202", "Create"), requests.last().formEntries.map { it.value })
    }

    @Test fun `live single stage change options return materials without posting the destructive create form`() {
        stub(TownFeatureId.CARD_CHANGE, CHANGE_URL, LIVE_CHANGE)

        val response = service.loadChangeOptions(7L, CardBaseOptionsRequest(changeBaseId()))

        assertEquals(changeBaseId(), response.selectedBaseCandidateId)
        assertEquals(listOf("Change Material x4 / ★"), response.materialCards.map { it.label })
        assertEquals(listOf(HofHttpMethod.GET), captureRequests().map(HofRequest::method))
    }

    @Test fun `live single stage change posts base material amount and create exactly once`() {
        stub(TownFeatureId.CARD_CHANGE, CHANGE_URL, LIVE_CHANGE, LIVE_CHANGE)

        service.change(7L, CardChangeRequest(changeBaseId(), changeMaterialId(), 2))

        val requests = captureRequests()
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf("ItemNo", "amount", "AddMaterial", "Create"), requests.last().formEntries.map { it.name })
        assertEquals(listOf("301", "2", "302", "Create"), requests.last().formEntries.map { it.value })
    }

    private fun stub(feature: TownFeatureId, url: String, vararg responses: String) {
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "card-user", "encrypted", Instant.EPOCH))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(feature, null)).thenReturn(ResolvedTownLocation(feature, url))
        val values = responses.map { HofHttpResponse(200, url, it, emptyMap()) }.toTypedArray()
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(values.first(), *values.drop(1).toTypedArray())
    }

    private fun captureRequests(): List<HofRequest> = Mockito.mockingDetails(gateway).invocations.mapNotNull { invocation ->
        invocation.arguments.getOrNull(1) as? HofRequest
    }

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, UPGRADE_URL)
    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()
    private fun snapshot() = CardPageParser().parseUpgrade(LIVE_UPGRADE, UPGRADE_URL, HofFormParser().parse(LIVE_UPGRADE, UPGRADE_URL))
    private fun changeSnapshot() = CardPageParser().parseChange(LIVE_CHANGE, CHANGE_URL, HofFormParser().parse(LIVE_CHANGE, CHANGE_URL))
    private fun baseId() = snapshot().baseCards.single().id
    private fun materialId() = snapshot().materialCards.single().id
    private fun changeBaseId() = changeSnapshot().baseCards.single().id
    private fun changeMaterialId() = changeSnapshot().materialCards.single().id

    private companion object {
        const val UPGRADE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=cardmix"
        const val CHANGE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=cardmix2"
        val LIVE_UPGRADE = """
            <html><body><form method="post" action="?menu=cardmix">
              <table>
                <tr><td></td><td>제작비</td><td>Item</td></tr>
                <tr><td><input type="radio" name="ItemNo" value="201"></td><td>${'$'} 100,000</td><td>Soul Taker's Card x2 / ★★ / Base Only</td></tr>
                <tr><td></td><td>제작비</td><td>Item</td></tr>
              </table>
              <input type="text" name="amount" value="1">
              <input type="radio" name="AddMaterial" value="202"> Material Card x3 / ★★
              <input type="submit" name="Create" value="Create">
            </form></body></html>
        """.trimIndent()
        val LIVE_CHANGE = """
            <html><body><form method="post" action="?menu=cardmix2">
              <table>
                <tr><td></td><td>제작비</td><td>Item</td></tr>
                <tr><td><input type="radio" name="ItemNo" value="301"></td><td>${'$'} 50,000</td><td>Change Base x3 / ★ / Base Only</td></tr>
                <tr><td></td><td>제작비</td><td>Item</td></tr>
              </table>
              <input type="text" name="amount" value="1">
              <input type="radio" name="AddMaterial" value="302"> Change Material x4 / ★
              <input type="submit" name="Create" value="Create">
            </form></body></html>
        """.trimIndent()
    }
}
