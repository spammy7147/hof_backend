package app.spammy.hof.party.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, PartyPresetFolderQueryRepository::class)
class PartyPresetFolderQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var folderRepository: PartyPresetFolderRepository

    @Autowired
    private lateinit var queryRepository: PartyPresetFolderQueryRepository

    @Test
    fun readsOwnedRootsFirstThenChildrenByParentDisplayOrderAndId() {
        val account = savedAccount("folder-query-owner")
        val otherAccount = savedAccount("folder-query-other")
        val firstRootTie = savedFolder(account, "첫 루트 동률", displayOrder = 1)
        val secondRootTie = savedFolder(account, "둘째 루트 동률", displayOrder = 1)
        val leadingRoot = savedFolder(account, "선두 루트", displayOrder = 0)
        val firstChildTie = savedFolder(account, "첫 자식 동률", displayOrder = 2, parent = firstRootTie)
        val secondChildTie = savedFolder(account, "둘째 자식 동률", displayOrder = 2, parent = firstRootTie)
        val leadingChild = savedFolder(account, "선두 자식", displayOrder = 0, parent = firstRootTie)
        val otherParentChild = savedFolder(account, "다른 부모 자식", displayOrder = 0, parent = secondRootTie)
        savedFolder(otherAccount, "다른 계정 루트", displayOrder = 0)

        assertEquals(
            listOf(
                leadingRoot.id,
                firstRootTie.id,
                secondRootTie.id,
                leadingChild.id,
                firstChildTie.id,
                secondChildTie.id,
                otherParentChild.id,
            ),
            queryRepository.findAllByAccountId(account.id).map { folder -> folder.id },
        )
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedFolder(
        account: HofAccountEntity,
        name: String,
        displayOrder: Int,
        parent: PartyPresetFolderEntity? = null,
    ): PartyPresetFolderEntity =
        folderRepository.save(
            PartyPresetFolderEntity(
                account = account,
                parent = parent,
                name = name,
                displayOrder = displayOrder,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-27T00:00:00Z")
    }
}
