package app.spammy.hof.town.shop

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.controller.ShopController
import app.spammy.hof.town.shop.dto.ShopResponse
import app.spammy.hof.town.shop.parser.ShopPageParser
import app.spammy.hof.town.shop.service.ShopService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.mockito.Mockito

class ShopControllerTest {
    private val formParser = HofFormParser()
    private val parser = ShopPageParser()

    @Test
    fun `공용 상점 목록 조회는 HOF 세션 복구나 captcha를 실행하지 않는다`() {
        val service = Mockito.mock(ShopService::class.java)
        val recovery = Mockito.mock(HofSessionRecoveryService::class.java)
        val expected = ShopResponse("dark", emptyList(), stale = false, lastVerifiedAt = null)
        Mockito.`when`(service.loadShop(ShopId.DARK)).thenReturn(expected)

        val actual = ShopController(service, recovery).loadShop("dark")

        assertEquals(expected, actual)
        Mockito.verifyNoInteractions(recovery)
    }

    @Test
    fun `catalog and zero-dollar sell rows remain selectable`() {
        val page = formParser.parse(ShopCatalogRefreshServiceTest.SHOP_HTML)
        val catalog = parser.parseCatalog(page)
        assertEquals(listOf("item-a", "item-b"), catalog.map { it.itemKey })

        val sell = formParser.parse("""<form method="post"><table><tr><td>$ 0</td><td><input type="checkbox" name="sell[]" value="free">Funds Bag x5</td></tr></table><button name="Sell" value="Sell">Sell</button></form>""")
        val item = parser.parseSell(sell).items.single()
        assertEquals(0, item.price)
        assertTrue(item.selectable)
    }

    @Test
    fun `multi-item cart preserves each requested quantity in one guarded form`() {
        val page = formParser.parse(ShopCatalogRefreshServiceTest.SHOP_HTML)
        val form = parser.purchaseForm(page)!!
        val guarded = TownActionGuard().guard(page, TownActionRequest(form.actionId, listOf(
            TownActionSelection("item-a", 2), TownActionSelection("item-b", 1),
        )))
        assertEquals(listOf("item-a", "item-b"), guarded.formEntries.filter { it.name == "item[]" }.map { it.value })
        assertEquals(listOf("2", "1"), guarded.formEntries.filter { it.name.startsWith("qty_") }.map { it.value })
    }

    @Test
    fun `combine exposes one primary and three secondary selects and guard emits no arbitrary field`() {
        val page = formParser.parse("""<form method="post">
          <select name="main"><option value="">선택</option><option value="m">Milk</option><option value="disabled" disabled>Disabled</option><optgroup disabled><option value="group-disabled">Group Disabled</option></optgroup></select>
          <select name="sub1"><option value="a">A</option></select><select name="sub2"><option value="b">B</option></select><select name="sub3"><option value="c">C</option></select>
          <input name="mix_times" type="number" min="1" max="10" value="1"><button name="Combine" value="Combine">Combine</button></form>""")
        val combine = parser.parseCombine(page)
        assertEquals(1, combine.primary.size)
        assertEquals(3, combine.secondarySlots.size)
        val form = parser.combineForm(page)!!
        val guarded = TownActionGuard().guard(page, TownActionRequest(form.actionId, listOf(
            TownActionSelection("main:m", 2), TownActionSelection("sub1:a"), TownActionSelection("sub2:b"), TownActionSelection("sub3:c"),
        )))
        assertEquals(listOf("main", "sub1", "sub2", "sub3", "mix_times", "Combine"), guarded.formEntries.map { it.name })
        assertTrue(guarded.formEntries.none { it.name == "actionUrl" || it.name == "csrf-from-app" })
        assertFails { TownActionGuard().guard(page, TownActionRequest(form.actionId, listOf(TownActionSelection("main:m"), TownActionSelection("main:m")))) }
    }

    @Test
    fun `four selects do not turn an unrelated text field into quantity`() {
        val page = formParser.parse("""<form method="post">
          <select name="main"><option value="m">Milk</option></select><select name="sub1"><option value="a">A</option></select>
          <select name="sub2"><option value="b">B</option></select><select name="sub3"><option value="c">C</option></select>
          <input name="search" type="text" value="query"><button name="Combine" value="Combine">Combine</button></form>""")
        val form = parser.combineForm(page)!!

        assertFails {
            TownActionGuard().guard(page, TownActionRequest(form.actionId, listOf(
                TownActionSelection("main:m", 2), TownActionSelection("sub1:a"), TownActionSelection("sub2:b"), TownActionSelection("sub3:c"),
            )))
        }
    }
}
