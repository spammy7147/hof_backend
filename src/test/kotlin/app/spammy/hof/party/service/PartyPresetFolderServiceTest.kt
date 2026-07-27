package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetFolderRequest
import app.spammy.hof.party.dto.MovePartyPresetFolderRequest
import app.spammy.hof.party.dto.RenamePartyPresetFolderRequest
import app.spammy.hof.party.dto.ReorderPartyPresetFoldersRequest
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetFolderRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    PartyPresetFolderQueryRepository::class,
    PartyPresetResponseMapper::class,
    PartyPresetFolderService::class,
    PartyPresetFolderServiceTest.FixedTimeConfiguration::class,
)
class PartyPresetFolderServiceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var folders: PartyPresetFolderRepository
    @MockitoSpyBean private lateinit var folderQueries: PartyPresetFolderQueryRepository
    @MockitoSpyBean private lateinit var accountQueries: AccountQueryRepository
    @Autowired private lateinit var service: PartyPresetFolderService
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun createsTrimmedRootAndChildFirstAndPersistsOrderAndTimestamps() {
        val account = account("create")
        val oldRoot = folder(account, "기존 루트", 0)
        val root = service.create(account.id, CreatePartyPresetFolderRequest("  새 루트  ", null))
        val child = service.create(account.id, CreatePartyPresetFolderRequest(" 자식 ", root.id))
        val secondChild = service.create(account.id, CreatePartyPresetFolderRequest("둘째", root.id))
        entityManager.flush()
        entityManager.clear()

        val persisted = folderQueries.findAllByAccountId(account.id).associateBy { it.id }
        assertEquals("새 루트", root.name)
        assertNull(root.parentFolderId)
        assertEquals(0, persisted.getValue(root.id).displayOrder)
        assertEquals(1, persisted.getValue(oldRoot.id).displayOrder)
        assertEquals(root.id, child.parentFolderId)
        assertEquals(1, persisted.getValue(child.id).displayOrder)
        assertEquals(0, persisted.getValue(secondChild.id).displayOrder)
        assertEquals(NOW, persisted.getValue(root.id).createdAt)
        assertEquals(NOW, persisted.getValue(root.id).updatedAt)
    }

    @Test
    fun createAndRenameValidateNameAndCaseInsensitiveSiblingUniqueness() {
        val account = account("names")
        val root = folder(account, "Alpha", 0)
        val other = folder(account, "Beta", 1)
        val child = folder(account, "alpha", 0, root)

        invalid { service.create(account.id, CreatePartyPresetFolderRequest("   ", null)) }
        invalid { service.create(account.id, CreatePartyPresetFolderRequest("x".repeat(101), null)) }
        invalid { service.create(account.id, CreatePartyPresetFolderRequest(" alpha ", null)) }
        assertEquals("alpha", service.rename(account.id, root.id, RenamePartyPresetFolderRequest(" alpha ")).name)
        invalid { service.rename(account.id, other.id, RenamePartyPresetFolderRequest(" ALPHA ")) }
        assertEquals("alpha", service.rename(account.id, child.id, RenamePartyPresetFolderRequest(" alpha ")).name)
        invalid { service.rename(account.id, child.id, RenamePartyPresetFolderRequest(" ")) }
    }

    @Test
    fun rejectsForeignOrMissingParentAndKeepsAccountsIsolated() {
        val owner = account("owner")
        val foreign = account("foreign")
        val foreignFolder = folder(foreign, "foreign", 0)

        notFound { service.create(owner.id, CreatePartyPresetFolderRequest("child", foreignFolder.id)) }
        notFound { service.create(owner.id, CreatePartyPresetFolderRequest("child", Long.MAX_VALUE)) }
        notFound { service.rename(owner.id, foreignFolder.id, RenamePartyPresetFolderRequest("changed")) }
        assertEquals("foreign", folderQueries.findAllByAccountId(foreign.id).single().name)
    }

    @Test
    fun rejectsCreatingLevelSix() {
        val account = account("depth")
        var parent: PartyPresetFolderEntity? = null
        repeat(5) { level -> parent = folder(account, "level-$level", 0, parent) }

        invalid { service.create(account.id, CreatePartyPresetFolderRequest("level-6", parent!!.id)) }
    }

    @Test
    fun reordersOnlyAnExactCompleteSiblingSetAndPersistsZeroBasedOrder() {
        val account = account("reorder")
        val a = folder(account, "a", 3)
        val b = folder(account, "b", 8)
        val parent = folder(account, "parent", 9)
        val child = folder(account, "child", 0, parent)

        val reordered = service.reorder(account.id, ReorderPartyPresetFoldersRequest(null, listOf(parent.id, b.id, a.id)))
        assertEquals(listOf(parent.id, b.id, a.id), reordered.map { it.id })
        entityManager.flush()
        assertEquals(listOf(0, 1, 2), listOf(parent, b, a).map { it.displayOrder })
        invalid { service.reorder(account.id, ReorderPartyPresetFoldersRequest(null, listOf(a.id, a.id, parent.id))) }
        invalid { service.reorder(account.id, ReorderPartyPresetFoldersRequest(null, listOf(a.id, b.id))) }
        invalid { service.reorder(account.id, ReorderPartyPresetFoldersRequest(null, listOf(a.id, b.id, parent.id, Long.MAX_VALUE))) }
        invalid { service.reorder(account.id, ReorderPartyPresetFoldersRequest(null, listOf(a.id, b.id, child.id))) }
    }

    @Test
    fun reorderRejectsForeignParentAndForeignFolderIds() {
        val owner = account("reorder-owner")
        val foreign = account("reorder-foreign")
        val owned = folder(owner, "owned", 0)
        val foreignParent = folder(foreign, "foreign parent", 0)
        val foreignChild = folder(foreign, "foreign child", 0, foreignParent)

        notFound { service.reorder(owner.id, ReorderPartyPresetFoldersRequest(foreignParent.id, listOf(foreignChild.id))) }
        invalid { service.reorder(owner.id, ReorderPartyPresetFoldersRequest(null, listOf(owned.id, foreignParent.id))) }
    }

    @Test
    fun movesRootToChildAndChildToRootWhileNormalizingBothSiblingGroups() {
        val account = account("cross-move")
        val destination = folder(account, "destination", 0)
        val moving = folder(account, "moving", 1)
        val rootTail = folder(account, "tail", 2)
        val existingChild = folder(account, "existing", 0, destination)

        service.move(account.id, moving.id, MovePartyPresetFolderRequest(destination.id, 1))
        assertEquals(destination.id, moving.parent?.id)
        assertEquals(listOf(0, 1), listOf(existingChild, moving).map { it.displayOrder })
        assertEquals(listOf(0, 1), listOf(destination, rootTail).map { it.displayOrder })

        service.move(account.id, moving.id, MovePartyPresetFolderRequest(null, 1))
        assertNull(moving.parent)
        assertEquals(listOf(0, 1, 2), listOf(destination, moving, rootTail).map { it.displayOrder })
        assertEquals(0, existingChild.displayOrder)
    }

    @Test
    fun movesWithinSameParentUsingFinalZeroBasedOrder() {
        val account = account("same-move")
        val a = folder(account, "a", 0)
        val b = folder(account, "b", 1)
        val c = folder(account, "c", 2)

        val response = service.move(account.id, a.id, MovePartyPresetFolderRequest(null, 2))

        assertEquals(listOf(b.id, c.id, a.id), response.map { it.id })
        assertEquals(listOf(0, 1, 2), listOf(b, c, a).map { it.displayOrder })
    }

    @Test
    fun moveRejectsBadOrdersMissingParentsSelfAndDescendantCycles() {
        val account = account("bad-move")
        val root = folder(account, "root", 0)
        val child = folder(account, "child", 0, root)

        invalid { service.move(account.id, root.id, MovePartyPresetFolderRequest(null, -1)) }
        invalid { service.move(account.id, root.id, MovePartyPresetFolderRequest(null, 2)) }
        notFound { service.move(account.id, root.id, MovePartyPresetFolderRequest(Long.MAX_VALUE, 0)) }
        invalid { service.move(account.id, root.id, MovePartyPresetFolderRequest(root.id, 0)) }
        invalid { service.move(account.id, root.id, MovePartyPresetFolderRequest(child.id, 0)) }
    }

    @Test
    fun moveRejectsForeignFolderAndParentWithoutDisclosure() {
        val owner = account("move-owner")
        val foreign = account("move-foreign")
        val owned = folder(owner, "owned", 0)
        val foreignFolder = folder(foreign, "foreign", 0)

        notFound { service.move(owner.id, foreignFolder.id, MovePartyPresetFolderRequest(null, 0)) }
        notFound { service.move(owner.id, owned.id, MovePartyPresetFolderRequest(foreignFolder.id, 0)) }
    }

    @Test
    fun moveRejectsWhenDeepestDescendantWouldExceedLevelFive() {
        val account = account("height")
        val destination1 = folder(account, "d1", 0)
        val destination2 = folder(account, "d2", 0, destination1)
        val destination3 = folder(account, "d3", 0, destination2)
        val moving = folder(account, "moving", 1)
        val child = folder(account, "moving-child", 0, moving)
        folder(account, "moving-grandchild", 0, child)

        invalid { service.move(account.id, moving.id, MovePartyPresetFolderRequest(destination3.id, 0)) }
    }

    @Test
    fun missingAccountFailsBeforeAnyFolderMutation() {
        clearInvocations(accountQueries, folderQueries)
        val error = assertFailsWith<ApiException> {
            service.create(Long.MAX_VALUE, CreatePartyPresetFolderRequest("never saved", null))
        }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        verify(accountQueries).findByIdForUpdate(Long.MAX_VALUE)
        verify(folderQueries, never()).findAllByAccountId(Long.MAX_VALUE)
        assertTrue(folderQueries.findAllByAccountId(Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun accountLockIsAcquiredBeforeFolderStateIsLoadedForAWrite() {
        val account = account("lock-boundary")
        val folder = folder(account, "folder", 0)
        clearInvocations(accountQueries, folderQueries)

        service.rename(account.id, folder.id, RenamePartyPresetFolderRequest("renamed"))

        inOrder(accountQueries, folderQueries).apply {
            verify(accountQueries).findByIdForUpdate(account.id)
            verify(folderQueries).findAllByAccountId(account.id)
        }
    }

    private fun account(login: String) = accounts.save(HofAccountEntity(loginId = login, encryptedPassword = "encrypted", createdAt = NOW))

    private fun folder(
        account: HofAccountEntity,
        name: String,
        order: Int,
        parent: PartyPresetFolderEntity? = null,
    ) = folders.save(PartyPresetFolderEntity(account = account, parent = parent, name = name, displayOrder = order, createdAt = EARLIER, updatedAt = EARLIER))

    private fun invalid(block: () -> Unit) = assertEquals(ErrorCode.INVALID_REQUEST, assertFailsWith<ApiException> { block() }.errorCode)
    private fun notFound(block: () -> Unit) = assertEquals(ErrorCode.RESOURCE_NOT_FOUND, assertFailsWith<ApiException> { block() }.errorCode)

    @TestConfiguration
    class FixedTimeConfiguration {
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    companion object {
        private val EARLIER = Instant.parse("2026-01-01T00:00:00Z")
        private val NOW = Instant.parse("2026-07-27T01:02:03Z")
    }
}
