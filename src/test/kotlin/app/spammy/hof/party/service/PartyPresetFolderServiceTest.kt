package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetFolderRequest
import app.spammy.hof.party.dto.MovePartyPresetFolderRequest
import app.spammy.hof.party.dto.RenamePartyPresetFolderRequest
import app.spammy.hof.party.dto.ReorderPartyPresetFoldersRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetFolderRepository
import app.spammy.hof.party.repository.PartyPresetMemberCommandRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.repository.PartyPresetRepository
import jakarta.persistence.EntityManager
import java.time.Duration
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.junit.jupiter.api.assertTimeout

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    PartyPresetFolderQueryRepository::class,
    PartyPresetQueryRepository::class,
    PartyPresetResponseMapper::class,
    PartyPresetCatalogService::class,
    PartyPresetFolderService::class,
    PartyPresetFolderServiceTest.FixedTimeConfiguration::class,
)
class PartyPresetFolderServiceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var folders: PartyPresetFolderRepository
    @Autowired private lateinit var presets: PartyPresetRepository
    @MockitoSpyBean private lateinit var presetQueries: PartyPresetQueryRepository
    @Autowired private lateinit var presetMembers: PartyPresetMemberCommandRepository
    @Autowired private lateinit var automationEntries: AutomationEntryCommandRepository
    @Autowired private lateinit var battleMaps: BattleAutomationMapCommandRepository
    @MockitoSpyBean private lateinit var folderQueries: PartyPresetFolderQueryRepository
    @MockitoSpyBean private lateinit var accountQueries: AccountQueryRepository
    @MockitoSpyBean private lateinit var catalogService: PartyPresetCatalogService
    @Autowired private lateinit var service: PartyPresetFolderService
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun createsTrimmedRootAndChildFirstAndPersistsOrderAndTimestamps() {
        val account = account("create")
        val oldRoot = folder(account, "기존 루트", 0)
        val preset = preset(account, "프리셋")
        val rootCatalog = service.create(account.id, CreatePartyPresetFolderRequest("  새 루트  ", null))
        val root = rootCatalog.folders.single { it.name == "새 루트" }
        val childCatalog = service.create(account.id, CreatePartyPresetFolderRequest(" 자식 ", root.id))
        val child = childCatalog.folders.single { it.name == "자식" }
        val finalCatalog = service.create(account.id, CreatePartyPresetFolderRequest("둘째", root.id))
        val secondChild = finalCatalog.folders.single { it.name == "둘째" }
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
        assertEquals(listOf(preset.id), rootCatalog.presets.map { it.id })
        assertEquals(listOf(0, 1), rootCatalog.folders.filter { it.parentFolderId == null }.map { it.displayOrder })
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
        val renamed = service.rename(account.id, root.id, RenamePartyPresetFolderRequest(" alpha "))
            .folders.single { it.id == root.id }
        assertEquals("alpha", renamed.name)
        assertEquals(NOW.toString(), renamed.updatedAt)
        invalid { service.rename(account.id, other.id, RenamePartyPresetFolderRequest(" ALPHA ")) }
        assertEquals("alpha", service.rename(account.id, child.id, RenamePartyPresetFolderRequest(" alpha ")).folders.single { it.id == child.id }.name)
        invalid { service.rename(account.id, child.id, RenamePartyPresetFolderRequest(" ")) }
        assertEquals("x".repeat(100), service.rename(account.id, other.id, RenamePartyPresetFolderRequest("x".repeat(100))).folders.single { it.id == other.id }.name)
        invalid { service.rename(account.id, other.id, RenamePartyPresetFolderRequest("x".repeat(101))) }
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
        assertEquals(listOf(parent.id, b.id, a.id), reordered.folders.filter { it.parentFolderId == null }.map { it.id })
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
        notFound { service.reorder(owner.id, ReorderPartyPresetFoldersRequest(Long.MAX_VALUE, emptyList())) }
        invalid { service.reorder(owner.id, ReorderPartyPresetFoldersRequest(null, listOf(owned.id, foreignParent.id))) }
    }

    @Test
    fun movesRootToChildAndChildToRootWhileNormalizingBothSiblingGroups() {
        val account = account("cross-move")
        val destination = folder(account, "destination", 0)
        val moving = folder(account, "moving", 1)
        val rootTail = folder(account, "tail", 2)
        val existingChild = folder(account, "existing", 0, destination)

        val childCatalog = service.move(account.id, moving.id, MovePartyPresetFolderRequest(destination.id, 1))
        assertEquals(destination.id, moving.parent?.id)
        assertEquals(listOf(0, 1), listOf(existingChild, moving).map { it.displayOrder })
        assertEquals(listOf(0, 1), listOf(destination, rootTail).map { it.displayOrder })
        assertEquals(
            listOf(destination.id to 0, rootTail.id to 1, existingChild.id to 0, moving.id to 1),
            childCatalog.folders.map { it.id to it.displayOrder },
        )
        assertEquals(NOW.toString(), childCatalog.folders.single { it.id == moving.id }.updatedAt)

        val rootCatalog = service.move(account.id, moving.id, MovePartyPresetFolderRequest(null, 1))
        assertNull(moving.parent)
        assertEquals(listOf(0, 1, 2), listOf(destination, moving, rootTail).map { it.displayOrder })
        assertEquals(0, existingChild.displayOrder)
        assertEquals(
            listOf(destination.id to 0, moving.id to 1, rootTail.id to 2, existingChild.id to 0),
            rootCatalog.folders.map { it.id to it.displayOrder },
        )
    }

    @Test
    fun movesWithinSameParentUsingFinalZeroBasedOrder() {
        val account = account("same-move")
        val a = folder(account, "a", 0)
        val b = folder(account, "b", 1)
        val c = folder(account, "c", 2)

        val response = service.move(account.id, a.id, MovePartyPresetFolderRequest(null, 2))

        assertEquals(listOf(b.id, c.id, a.id), response.folders.map { it.id })
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
    fun corruptedExistingCycleTerminatesWithApiException() {
        val account = account("corrupted-cycle")
        val root = folder(account, "root", 0)
        val child = folder(account, "child", 0, root)
        entityManager.flush()
        entityManager.createNativeQuery(
            "update party_preset_folders set parent_folder_id = :childId where id = :rootId",
        ).setParameter("childId", child.id).setParameter("rootId", root.id).executeUpdate()
        entityManager.clear()

        assertTimeout(Duration.ofSeconds(1)) {
            invalid { service.move(account.id, root.id, MovePartyPresetFolderRequest(null, 0)) }
        }
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
    fun accountLockIsAcquiredBeforeFolderStateIsLoadedForEveryWrite() {
        val account = account("lock-boundary")
        val folder = folder(account, "folder", 0)
        assertLockBeforeLoad(account.id) { service.create(account.id, CreatePartyPresetFolderRequest("created", null)) }
        assertLockBeforeLoad(account.id) { service.rename(account.id, folder.id, RenamePartyPresetFolderRequest("renamed")) }
        val rootIds = folderQueries.findAllByAccountId(account.id).filter { it.parent == null }.map { it.id }
        assertLockBeforeLoad(account.id) { service.reorder(account.id, ReorderPartyPresetFoldersRequest(null, rootIds)) }
        assertLockBeforeLoad(account.id) { service.move(account.id, folder.id, MovePartyPresetFolderRequest(null, 1)) }
        assertLockBeforeLoad(account.id) { service.delete(account.id, folder.id) }
    }

    @Test
    fun deletesRootByUnassigningDirectPresetsAndPromotingOnlyImmediateChildrenWithoutLosingReferences() {
        val account = account("delete-root")
        val existingRoot = folder(account, "existing-root", 0)
        val deleting = folder(account, "deleting", 4)
        val rootTail = folder(account, "root-tail", 8)
        val childB = folder(account, "child-b", 7, deleting)
        val childA = folder(account, "child-a", 7, deleting)
        val grandchild = folder(account, "grandchild", 3, childA)
        val existingUnassigned = preset(account, "existing-unassigned", order = 9)
        val directB = preset(account, "direct-b", order = 5, folder = deleting)
        val directA = preset(account, "direct-a", order = 5, folder = deleting, primary = true)
        val untouched = preset(account, "untouched", order = 11, folder = existingRoot)
        presetMembers.saveAll((0..4).map { PartyPresetMemberEntity(directA, it) })
        val automationEntry = automationEntries.save(
            AutomationEntryEntity(
                account = account,
                type = AutomationType.BATTLE_MAP,
                priority = 0,
                enabled = true,
                createdAt = EARLIER,
                updatedAt = EARLIER,
            ),
        )
        val automationMap = battleMaps.save(
            BattleAutomationMapEntity(
                entry = automationEntry,
                categoryId = "battle_map",
                mapCode = "gb0",
                dailyTargetCount = 1,
                presetMode = PresetSelectionMode.EXPLICIT,
                partyPreset = directA,
                executionOrder = 0,
            ),
        )
        entityManager.flush()

        val response = service.delete(account.id, deleting.id)
        entityManager.flush()
        entityManager.clear()

        assertEquals(
            listOf(existingRoot.id, rootTail.id, childB.id, childA.id),
            response.folders.filter { it.parentFolderId == null }.map { it.id },
        )
        assertEquals(listOf(0, 1, 2, 3), response.folders.filter { it.parentFolderId == null }.map { it.displayOrder })
        assertEquals(childA.id, response.folders.single { it.id == grandchild.id }.parentFolderId)
        assertEquals(
            listOf(existingUnassigned.id, directB.id, directA.id),
            response.presets.filter { it.folderId == null }.map { it.id },
        )
        assertEquals(listOf(0, 1, 2), response.presets.filter { it.folderId == null }.map { it.displayOrder })
        val primaryResponse = response.presets.single { it.id == directA.id }
        assertEquals("direct-a", primaryResponse.name)
        assertTrue(primaryResponse.isPrimary)
        assertEquals((0..4).toList(), primaryResponse.members.map { it.slotIndex })
        assertEquals(EARLIER.toString(), primaryResponse.createdAt)
        assertEquals(EARLIER.toString(), primaryResponse.updatedAt)
        assertEquals(existingRoot.id, response.presets.single { it.id == untouched.id }.folderId)
        assertEquals(4, presetQueries.findAllByAccountId(account.id).size)
        assertEquals(5L, entityManager.createNativeQuery("select count(*) from party_preset_members where preset_id = :id")
            .setParameter("id", directA.id).singleResult.toString().toLong())
        assertEquals(directA.id, entityManager.createNativeQuery("select party_preset_id from battle_automation_maps where id = :id")
            .setParameter("id", automationMap.id).singleResult.toString().toLong())
    }

    @Test
    fun deletesNestedFolderByAppendingChildrenToParentSiblingsAndPreservingGrandchildren() {
        val account = account("delete-nested")
        val parent = folder(account, "parent", 0)
        val existingA = folder(account, "existing-a", 2, parent)
        val deleting = folder(account, "deleting", 4, parent)
        val existingB = folder(account, "existing-b", 8, parent)
        val childB = folder(account, "child-b", 6, deleting)
        val childA = folder(account, "child-a", 6, deleting)
        val grandchild = folder(account, "grandchild", 0, childB)
        val directB = preset(account, "direct-b", order = 9, folder = deleting)
        val directA = preset(account, "direct-a", order = 9, folder = deleting)

        val response = service.delete(account.id, deleting.id)

        assertEquals(
            listOf(existingA.id, existingB.id, childB.id, childA.id),
            response.folders.filter { it.parentFolderId == parent.id }.map { it.id },
        )
        assertEquals(listOf(0, 1, 2, 3), response.folders.filter { it.parentFolderId == parent.id }.map { it.displayOrder })
        assertEquals(childB.id, response.folders.single { it.id == grandchild.id }.parentFolderId)
        assertEquals(listOf(directB.id, directA.id), response.presets.filter { it.folderId == null }.map { it.id })
        assertEquals(listOf(0, 1), response.presets.filter { it.folderId == null }.map { it.displayOrder })
    }

    @Test
    fun deletesEmptyNestedFolderWithoutAffectingParentSiblingsOrUnrelatedCatalogEntries() {
        val account = account("delete-empty-nested")
        val parent = folder(account, "parent", 0)
        val unrelatedRoot = folder(account, "unrelated-root", 5)
        val siblingA = folder(account, "sibling-a", 2, parent)
        val deleting = folder(account, "deleting", 4, parent)
        val siblingB = folder(account, "sibling-b", 8, parent)
        val unrelatedPreset = preset(account, "unrelated-preset", order = 6, folder = unrelatedRoot)

        val response = service.delete(account.id, deleting.id)

        assertTrue(response.folders.none { it.id == deleting.id })
        assertEquals(parent.id, response.folders.single { it.id == parent.id }.id)
        assertEquals(
            listOf(siblingA.id, siblingB.id),
            response.folders.filter { it.parentFolderId == parent.id }.map { it.id },
        )
        assertEquals(listOf(0, 1), response.folders.filter { it.parentFolderId == parent.id }.map { it.displayOrder })
        assertEquals(0, response.folders.single { it.id == parent.id }.displayOrder)
        assertEquals(5, response.folders.single { it.id == unrelatedRoot.id }.displayOrder)
        val presetResponse = response.presets.single()
        assertEquals(unrelatedPreset.id, presetResponse.id)
        assertEquals(unrelatedRoot.id, presetResponse.folderId)
        assertEquals(6, presetResponse.displayOrder)
        assertEquals("unrelated-preset", presetResponse.name)
        entityManager.flush()
        entityManager.clear()
        assertEquals(
            setOf(parent.id, unrelatedRoot.id, siblingA.id, siblingB.id),
            folderQueries.findAllByAccountId(account.id).map { it.id }.toSet(),
        )
        assertEquals(unrelatedRoot.id, presetQueries.findAllByAccountId(account.id).single().folder?.id)
    }

    @Test
    fun deletesEmptyFolderAndRejectsForeignOrMissingFolderWithoutMutation() {
        val owner = account("delete-owner")
        val foreign = account("delete-foreign")
        val empty = folder(owner, "empty", 0)
        val survivor = folder(owner, "survivor", 1)
        val foreignFolder = folder(foreign, "foreign", 0)

        val response = service.delete(owner.id, empty.id)
        assertEquals(listOf(survivor.id), response.folders.map { it.id })
        assertEquals(0, response.folders.single().displayOrder)
        notFound { service.delete(owner.id, foreignFolder.id) }
        notFound { service.delete(owner.id, Long.MAX_VALUE) }
        assertEquals(listOf(survivor.id), folderQueries.findAllByAccountId(owner.id).map { it.id })
        assertEquals(listOf(foreignFolder.id), folderQueries.findAllByAccountId(foreign.id).map { it.id })
    }

    @Test
    fun deleteLocksAccountBeforeLoadingFoldersOrPresets() {
        val account = account("delete-lock")
        val deleting = folder(account, "deleting", 0)
        clearInvocations(accountQueries, folderQueries, presetQueries)

        service.delete(account.id, deleting.id)

        inOrder(accountQueries, folderQueries, presetQueries).apply {
            verify(accountQueries).findByIdForUpdate(account.id)
            verify(folderQueries).findAllByAccountId(account.id)
            verify(presetQueries).findAllByAccountIdAndFolderId(account.id, null)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun rollsBackAllWritesWhenAuthoritativeCatalogLoadingFails() {
        val account = account("delete-rollback")
        val deleting = folder(account, "deleting", 3)
        val child = folder(account, "child", 5, deleting)
        val direct = preset(account, "direct", order = 7, folder = deleting)
        doThrow(IllegalStateException("forced catalog failure"))
            .doCallRealMethod()
            .`when`(catalogService).find(account.id)

        assertFailsWith<IllegalStateException> { service.delete(account.id, deleting.id) }
        entityManager.clear()

        val persistedFolders = folderQueries.findAllByAccountId(account.id).associateBy { it.id }
        val persistedPreset = presetQueries.findAllByAccountId(account.id).single()
        assertEquals(deleting.id, persistedFolders.getValue(child.id).parent?.id)
        assertEquals(5, persistedFolders.getValue(child.id).displayOrder)
        assertEquals(deleting.id, persistedPreset.folder?.id)
        assertEquals(7, persistedPreset.displayOrder)
    }

    private fun assertLockBeforeLoad(accountId: Long, block: () -> Unit) {
        clearInvocations(accountQueries, folderQueries)
        block()
        inOrder(accountQueries, folderQueries).apply {
            verify(accountQueries).findByIdForUpdate(accountId)
            verify(folderQueries, times(2)).findAllByAccountId(accountId)
        }
    }

    private fun account(login: String) = accounts.save(HofAccountEntity(loginId = login, encryptedPassword = "encrypted", createdAt = NOW))

    private fun folder(
        account: HofAccountEntity,
        name: String,
        order: Int,
        parent: PartyPresetFolderEntity? = null,
    ) = folders.save(PartyPresetFolderEntity(account = account, parent = parent, name = name, displayOrder = order, createdAt = EARLIER, updatedAt = EARLIER))

    private fun preset(
        account: HofAccountEntity,
        name: String,
        order: Int = 0,
        folder: PartyPresetFolderEntity? = null,
        primary: Boolean = false,
    ) = presets.save(
        PartyPresetEntity(
            account = account,
            name = name,
            displayOrder = order,
            folder = folder,
            isPrimary = primary,
            createdAt = EARLIER,
            updatedAt = EARLIER,
        ),
    )

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
