package app.spammy.hof.external.client

import app.spammy.hof.external.model.HofBattleType
import app.spammy.hof.external.model.HofHttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class HofRequestFactoryTest {
    private val factory = HofRequestFactory()

    @Test
    fun buildsLoginRequest() {
        val request = factory.login(id = "user", password = "pass")

        assertEquals(HofHttpMethod.POST, request.method)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php", request.url)
        assertEquals(
            mapOf("id" to "user", "pass" to "pass", "Login" to "login"),
            request.formFields,
        )
    }

    @Test
    fun buildsPatternLoadRequest() {
        val request = factory.loadPattern(characterId = "1683198503393759", slot = 0)

        assertEquals(HofHttpMethod.POST, request.method)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?char=1683198503393759", request.url)
        assertEquals(mapOf("patternno" to "0", "loadpattern" to "LOAD"), request.formFields)
    }

    @Test
    fun buildsThreeRoundCommonBattleRequest() {
        val request = factory.battle(
            type = HofBattleType.COMMON,
            code = "snow22",
            characterIds = listOf("111", "222", "333", "444", "555"),
            battleCount = 3,
        )

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?common=snow22", request.url)
        assertEquals(
            mapOf(
                "char_111" to "1",
                "char_222" to "1",
                "char_333" to "1",
                "char_444" to "1",
                "char_555" to "1",
                "monster_battle_10" to "Battle !",
            ),
            request.formFields,
        )
    }

    @Test
    fun buildsUnionBattleRequest() {
        val request = factory.battle(
            type = HofBattleType.UNION,
            code = "union001",
            characterIds = listOf("111"),
            battleCount = 1,
        )

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?union=union001", request.url)
        assertEquals(
            mapOf(
                "char_111" to "1",
                "monster_battle" to "Battle !",
            ),
            request.formFields,
        )
    }
}
