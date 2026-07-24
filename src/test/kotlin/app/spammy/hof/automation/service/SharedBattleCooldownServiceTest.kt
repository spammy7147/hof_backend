package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.AccountBattleMapStateCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito

class SharedBattleCooldownServiceTest {
    private val account = HofAccountEntity(7L, "login", "encrypted", NOW)
    private val newMap = map(1L, "raid", "new", learned = false)
    private val knownMap = map(2L, "raid", "known", learned = true)
    private val ordinaryMap = map(3L, "battle_map", "ordinary", learned = false)
    private val newState = state(newMap)
    private val knownState = state(knownMap)
    private val ordinaryState = state(ordinaryMap)
    private val maps = Mockito.mock(BattleMapQueryRepository::class.java)
    private val mapCommands = Mockito.mock(BattleMapRepository::class.java)
    private val stateCommands = Mockito.mock(AccountBattleMapStateCommandRepository::class.java)
    private val service = SharedBattleCooldownService(
        maps = maps,
        mapCommands = mapCommands,
        stateCommands = stateCommands,
        timeProvider = TimeProvider { NOW },
    )

    init {
        Mockito.`when`(maps.findStateForExecution(7L, "raid", "new")).thenReturn(newState)
        Mockito.`when`(maps.findStateForExecution(7L, "raid", "known")).thenReturn(knownState)
        Mockito.`when`(maps.findStateForExecution(7L, "battle_map", "ordinary")).thenReturn(ordinaryState)
        Mockito.`when`(maps.findAllStatesForExecution(7L))
            .thenReturn(listOf(newState, knownState, ordinaryState))
    }

    @Test
    fun `rejection learns current map and applies later expiry to all known account members`() {
        service.learnAndApply(7L, "raid", "new", NOW.plusSeconds(56))

        assertTrue(newMap.sharesMinuteCooldown)
        assertEquals(NOW.plusSeconds(56), newState.cooldownUntil)
        assertEquals(NOW.plusSeconds(56), knownState.cooldownUntil)
        assertNull(ordinaryState.cooldownUntil)
        Mockito.verify(mapCommands).save(newMap)
        Mockito.verify(stateCommands).saveAll(listOf(newState, knownState))
    }

    @Test
    fun `shorter stale rejection never moves a shared expiry backwards`() {
        knownState.cooldownUntil = NOW.plusSeconds(50)

        service.learnAndApply(7L, "raid", "known", NOW.plusSeconds(20))

        assertEquals(NOW.plusSeconds(50), knownState.cooldownUntil)
        Mockito.verify(mapCommands, Mockito.never()).save(knownMap)
        Mockito.verify(stateCommands, Mockito.never()).saveAll(Mockito.anyList())
    }

    @Test
    fun `successful learned map opens one minute gate while ordinary success does nothing`() {
        assertTrue(service.applyAfterSuccessfulBattle(7L, "raid", "known"))
        assertEquals(NOW.plusSeconds(60), knownState.cooldownUntil)
        assertFalse(service.applyAfterSuccessfulBattle(7L, "battle_map", "ordinary"))
        assertNull(ordinaryState.cooldownUntil)
    }

    private fun map(
        id: Long,
        categoryId: String,
        mapCode: String,
        learned: Boolean,
    ) = BattleMapEntity(
        id = id,
        categoryId = categoryId,
        mapCode = mapCode,
        name = mapCode,
        normalizedName = mapCode,
        sharesMinuteCooldown = learned,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun state(map: BattleMapEntity) = AccountBattleMapStateEntity(
        account = account,
        battleMap = map,
        rawHref = map.mapCode,
        lastSeenAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-24T00:00:00Z")
    }
}
