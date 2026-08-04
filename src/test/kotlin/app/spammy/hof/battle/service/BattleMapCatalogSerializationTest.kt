package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.external.model.HofBattleMap
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class BattleMapCatalogSerializationTest {
    @Test
    fun serializesTransactionalSynchronizationCallsWithinTheJvm() {
        val transactionService = Mockito.mock(BattleMapCatalogTransactionService::class.java)
        val service = BattleMapCatalogService(transactionService)
        val firstEntered = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondDelegateEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delegateCalls = AtomicInteger()
        val activeDelegates = AtomicInteger()
        val maxActiveDelegates = AtomicInteger()
        Mockito.doAnswer {
            val callNumber = delegateCalls.incrementAndGet()
            val active = activeDelegates.incrementAndGet()
            maxActiveDelegates.updateAndGet { current -> maxOf(current, active) }
            if (callNumber == 1) {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            } else {
                secondDelegateEntered.countDown()
            }
            activeDelegates.decrementAndGet()
            emptyList<HofBattleMap>()
        }.`when`(transactionService).synchronizeCategory(anyAccount(), eqCategory(CATEGORY), anyObservations())

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<List<HofBattleMap>> {
                service.synchronizeCategory(ACCOUNT, CATEGORY, emptyList())
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<List<HofBattleMap>> {
                secondAttempted.countDown()
                service.synchronizeCategory(ACCOUNT, CATEGORY, emptyList())
            }
            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                secondDelegateEntered.await(100, TimeUnit.MILLISECONDS),
                "두 번째 transaction delegate는 첫 번째 synchronization fence가 해제되기 전에 진입하면 안 된다",
            )
            assertEquals(1, delegateCalls.get())
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(2, delegateCalls.get())
            assertEquals(1, maxActiveDelegates.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun anyAccount(): HofAccountEntity {
        Mockito.any(HofAccountEntity::class.java)
        return ACCOUNT
    }

    private fun eqCategory(value: String): String {
        Mockito.eq(value)
        return value
    }

    private fun anyObservations(): List<HofBattleMap> {
        Mockito.anyList<HofBattleMap>()
        return emptyList()
    }

    private companion object {
        const val CATEGORY = "adventure_map"
        val ACCOUNT = HofAccountEntity(
            id = 1L,
            loginId = "serialized-sync",
            encryptedPassword = "encrypted",
            createdAt = Instant.parse("2026-07-08T00:00:00Z"),
        )
    }
}
