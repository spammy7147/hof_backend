package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetMemberRequest
import app.spammy.hof.party.dto.PartyPresetResponse
import app.spammy.hof.party.dto.ReorderPartyPresetsRequest
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CharacterQueryRepository::class,
    PartyPresetQueryRepository::class,
    PartyPresetResponseMapper::class,
    PartyPresetService::class,
    PartyPresetConcurrencyTest.ClockConfig::class,
)
class PartyPresetConcurrencyTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var presetQueryRepository: PartyPresetQueryRepository

    @Autowired
    private lateinit var service: PartyPresetService

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun concurrentFirstPromotionsCompleteWithExactlyOnePrimaryMarker() {
        val accountId = savedAccount("party-concurrent-primary")
        val first = service.create(accountId, request("첫 번째"))
        val second = service.create(accountId, request("두 번째"))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = listOf(first.id, second.id).map { presetId ->
                executor.submit<PartyPresetResponse> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    service.makePrimary(accountId, presetId)
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }

            val stored = requireNotNull(TransactionTemplate(transactionManager).execute {
                presetQueryRepository.findAllByAccountId(accountId)
                    .map { preset -> preset.isPrimary to preset.primaryMarker }
            })
            assertEquals(1, stored.count { (isPrimary, _) -> isPrimary })
            assertEquals(1, stored.count { (_, marker) -> marker == 1 })
            assertTrue(stored.all { (isPrimary, marker) -> isPrimary == (marker == 1) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentUpdateAndMakePrimaryPreserveBothNameAndPrimaryState() {
        val accountId = savedAccount("party-update-lock")
        val preset = service.create(accountId, request("변경 전"))
        val makePrimaryReturned = CountDownLatch(1)
        val allowPrimaryCommit = CountDownLatch(1)
        val updateStarted = CountDownLatch(1)
        val updateFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val primaryFuture = executor.submit<PartyPresetResponse> {
            requireNotNull(TransactionTemplate(transactionManager).execute {
                val response = service.makePrimary(accountId, preset.id)
                makePrimaryReturned.countDown()
                check(allowPrimaryCommit.await(10, TimeUnit.SECONDS))
                response
            })
        }

        try {
            check(makePrimaryReturned.await(10, TimeUnit.SECONDS))
            val updateFuture = executor.submit<PartyPresetResponse> {
                updateStarted.countDown()
                try {
                    service.update(
                        accountId,
                        preset.id,
                        UpdatePartyPresetRequest(name = "변경 후", members = members()),
                    )
                } finally {
                    updateFinished.countDown()
                }
            }
            check(updateStarted.await(10, TimeUnit.SECONDS))

            try {
                assertFalse(
                    updateFinished.await(500, TimeUnit.MILLISECONDS),
                    "update must wait for make-primary's account mutation lock",
                )
            } finally {
                allowPrimaryCommit.countDown()
                primaryFuture.get(10, TimeUnit.SECONDS)
            }

            assertEquals("변경 후", updateFuture.get(10, TimeUnit.SECONDS).name)
            val stored = requireNotNull(TransactionTemplate(transactionManager).execute {
                presetQueryRepository.findOwnedByAccountIdAndId(accountId, preset.id)
            })
            assertEquals("변경 후", stored.name)
            assertTrue(stored.isPrimary)
            assertEquals(1, stored.primaryMarker)
        } finally {
            allowPrimaryCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentCreateAndReorderLeaveACompleteContiguousOrder() {
        val accountId = savedAccount("party-create-reorder-lock")
        val first = service.create(accountId, request("첫 번째"))
        val second = service.create(accountId, request("두 번째"))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val createFuture = executor.submit<PartyPresetResponse> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                service.create(accountId, request("세 번째"))
            }
            val reorderFuture = executor.submit<List<PartyPresetResponse>> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                service.reorder(accountId, ReorderPartyPresetsRequest(listOf(first.id, second.id)))
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            createFuture.get(10, TimeUnit.SECONDS)
            try {
                reorderFuture.get(10, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                assertTrue(error.cause is ApiException)
            }

            val stored = service.findAll(accountId)
            assertEquals(3, stored.map { preset -> preset.id }.toSet().size)
            assertEquals(listOf(0, 1, 2), stored.map { preset -> preset.displayOrder })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun savedAccount(loginId: String): Long =
        requireNotNull(TransactionTemplate(transactionManager).execute {
            accountRepository.save(
                HofAccountEntity(
                    loginId = loginId,
                    encryptedPassword = "encrypted",
                    createdAt = NOW,
                ),
            ).also { accountRepository.flush() }.id
        })

    private fun request(name: String): CreatePartyPresetRequest =
        CreatePartyPresetRequest(name = name, members = members())

    private fun members(): List<PartyPresetMemberRequest> =
        (0..4).map { slotIndex ->
            PartyPresetMemberRequest(
                slotIndex = slotIndex,
                characterId = null,
                patternSlot = null,
            )
        }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfig {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}
