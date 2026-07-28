package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.PartyPresetMemberRequest
import app.spammy.hof.party.dto.PartyPresetResponse
import app.spammy.hof.party.dto.ReorderPartyPresetsRequest
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetFolderRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.mockito.Mockito.doAnswer

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CharacterQueryRepository::class,
    PartyPresetQueryRepository::class,
    PartyPresetFolderQueryRepository::class,
    PartyPresetResponseMapper::class,
    PartyPresetCatalogService::class,
    PartyPresetService::class,
    PartyPresetConcurrencyTest.ClockConfig::class,
)
class PartyPresetConcurrencyTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @MockitoSpyBean
    private lateinit var accountQueryRepository: AccountQueryRepository

    @Autowired
    private lateinit var presetQueryRepository: PartyPresetQueryRepository

    @Autowired
    private lateinit var folderRepository: PartyPresetFolderRepository

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
    fun concurrentCreateAndReorderInSameFolderSerializeAndRejectStaleMembership() {
        val accountId = savedAccount("party-folder-create-reorder-lock")
        val folderId = savedFolder(accountId, "폴더")
        val first = service.create(accountId, request("첫 번째", folderId))
        val second = service.create(accountId, request("두 번째", folderId))
        val createReturned = CountDownLatch(1)
        val allowCreateCommit = CountDownLatch(1)
        val reorderReachedAccountLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val createFuture = executor.submit<PartyPresetResponse> {
                requireNotNull(TransactionTemplate(transactionManager).execute {
                    service.create(accountId, request("세 번째", folderId)).also {
                        createReturned.countDown()
                        check(allowCreateCommit.await(10, TimeUnit.SECONDS))
                    }
                })
            }
            check(createReturned.await(10, TimeUnit.SECONDS))
            doAnswer { invocation ->
                reorderReachedAccountLock.countDown()
                invocation.callRealMethod()
            }.`when`(accountQueryRepository).findByIdForUpdate(accountId)
            val reorderFuture = executor.submit<PartyPresetCatalogResponse> {
                service.reorder(accountId, ReorderPartyPresetsRequest(folderId, listOf(first.id, second.id)))
            }
            check(reorderReachedAccountLock.await(10, TimeUnit.SECONDS))
            allowCreateCommit.countDown()
            val created = createFuture.get(10, TimeUnit.SECONDS)
            val error = assertExecutionApiException(reorderFuture)
            assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)

            val siblings = service.findAll(accountId).filter { it.folderId == folderId }
            assertEquals(setOf(created.id, first.id, second.id), siblings.map { it.id }.toSet())
            assertEquals(listOf(0, 1, 2), siblings.map { it.displayOrder })
            assertEquals(created.id, siblings.first().id)
        } finally {
            allowCreateCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentMoveAndDestinationReorderSerializeWithoutOverwritingNewMembership() {
        val accountId = savedAccount("party-move-reorder-lock")
        val sourceId = savedFolder(accountId, "출발")
        val destinationId = savedFolder(accountId, "도착")
        val moving = service.create(accountId, request("이동", sourceId))
        val sourcePeer = service.create(accountId, request("출발 형제", sourceId))
        val destinationFirst = service.create(accountId, request("도착 첫째", destinationId))
        val destinationSecond = service.create(accountId, request("도착 둘째", destinationId))
        val moveReturned = CountDownLatch(1)
        val allowMoveCommit = CountDownLatch(1)
        val reorderReachedAccountLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val moveFuture = executor.submit<PartyPresetResponse> {
                requireNotNull(TransactionTemplate(transactionManager).execute {
                    service.update(accountId, moving.id, UpdatePartyPresetRequest("이동 완료", members(), destinationId)).also {
                        moveReturned.countDown()
                        check(allowMoveCommit.await(10, TimeUnit.SECONDS))
                    }
                })
            }
            check(moveReturned.await(10, TimeUnit.SECONDS))
            doAnswer { invocation ->
                reorderReachedAccountLock.countDown()
                invocation.callRealMethod()
            }.`when`(accountQueryRepository).findByIdForUpdate(accountId)
            val reorderFuture = executor.submit<PartyPresetCatalogResponse> {
                service.reorder(
                    accountId,
                    ReorderPartyPresetsRequest(destinationId, listOf(destinationFirst.id, destinationSecond.id)),
                )
            }
            check(reorderReachedAccountLock.await(10, TimeUnit.SECONDS))
            allowMoveCommit.countDown()
            moveFuture.get(10, TimeUnit.SECONDS)
            val error = assertExecutionApiException(reorderFuture)
            assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)

            val stored = service.findAll(accountId)
            val source = stored.filter { it.folderId == sourceId }
            val destination = stored.filter { it.folderId == destinationId }
            assertEquals(listOf(sourcePeer.id), source.map { it.id })
            assertEquals(listOf(0), source.map { it.displayOrder })
            assertEquals(setOf(moving.id, destinationFirst.id, destinationSecond.id), destination.map { it.id }.toSet())
            assertEquals(listOf(0, 1, 2), destination.map { it.displayOrder })
        } finally {
            allowMoveCommit.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertExecutionApiException(future: java.util.concurrent.Future<*>): ApiException {
        val error = assertFailsWith<ExecutionException> { future.get(10, TimeUnit.SECONDS) }
        return error.cause as ApiException
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

    private fun request(name: String, folderId: Long? = null): CreatePartyPresetRequest =
        CreatePartyPresetRequest(name = name, members = members(), folderId = folderId)

    private fun savedFolder(accountId: Long, name: String): Long =
        requireNotNull(TransactionTemplate(transactionManager).execute {
            val account = requireNotNull(accountQueryRepository.findById(accountId))
            folderRepository.save(
                PartyPresetFolderEntity(
                    account = account,
                    name = name,
                    displayOrder = 0,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            ).also { folderRepository.flush() }.id
        })

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
