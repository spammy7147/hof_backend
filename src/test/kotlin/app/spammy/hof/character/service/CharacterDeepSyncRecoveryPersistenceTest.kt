package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterRecoveryQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofEquipment
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@ActiveProfiles("test")
@DataJpaTest
@Import(CharacterDeepSyncRecovery::class, CharacterRecoveryQueryRepository::class, CharacterOperationJobQueryRepository::class,
    app.spammy.hof.account.repository.AccountQueryRepository::class, QueryDslConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CharacterDeepSyncRecoveryPersistenceTest {
    @Autowired private lateinit var recovery: CharacterDeepSyncRecovery
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var jobs: CharacterOperationJobCommandRepository
    @Autowired private lateinit var characters: CharacterRepository
    @MockitoBean private lateinit var time: TimeProvider
    @MockitoBean private lateinit var automation: CharacterOperationAutomation
    private lateinit var job: CharacterOperationJobEntity
    private val now = Instant.parse("2026-09-07T00:00:00Z")
    private val original = CharacterRestoreState(
        "hof-10", listOf(HofActionPatternRow(0, judge = "1000", quantity = "42", skill = "9564")),
        listOf(HofEquipment("weapon", "Weapon", "검 [카드 변형]", "/sword.gif", "Atk +10")),
        "front", "1",
    )

    @BeforeEach
    fun createJob() {
        Mockito.`when`(time.now()).thenReturn(now)
        val account = accounts.save(HofAccountEntity(loginId = "recovery-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = now))
        val character = characters.save(CharacterEntity(account = account, hofCharacterId = "hof-10", name = "fixture", job = "Knight", updatedAt = now))
        job = jobs.save(CharacterOperationJobEntity(
            account = account, operationType = CharacterOperationType.DEEP_SYNC, targetCharacterId = character.id,
            startedAt = now, updatedAt = now, recoveryStatus = CharacterRecoveryStatus.NOT_STARTED,
        ))
    }

    @Test
    fun `committed original and recovery stages survive separate transactions`() {
        assertNull(recovery.load(job.id, job.account.id, job.targetCharacterId))
        val checkpoint = CharacterDeepSyncCheckpoint(original)

        recovery.save(job.id, job.account.id, job.targetCharacterId, checkpoint)
        assertEquals(checkpoint, recovery.load(job.id, job.account.id, job.targetCharacterId))

        val restoring = checkpoint.copy(status = CharacterRecoveryStatus.RESTORING, collectionComplete = true, restoreAttempts = 1)
        recovery.save(job.id, job.account.id, job.targetCharacterId, restoring)
        assertEquals(restoring, recovery.load(job.id, job.account.id, job.targetCharacterId))

        val restored = restoring.copy(status = CharacterRecoveryStatus.RESTORED)
        recovery.save(job.id, job.account.id, job.targetCharacterId, restored)
        assertEquals(restored, recovery.load(job.id, job.account.id, job.targetCharacterId))
    }

    @Test
    fun `a repeated capture cannot replace the first original`() {
        recovery.save(job.id, job.account.id, job.targetCharacterId, CharacterDeepSyncCheckpoint(original))

        assertFailsWith<IllegalStateException> {
            recovery.save(job.id, job.account.id, job.targetCharacterId, CharacterDeepSyncCheckpoint(original.copy(position = "back")))
        }

        assertEquals(original, assertNotNull(recovery.load(job.id, job.account.id, job.targetCharacterId)).original)
    }

    @Test
    fun `another account or character cannot read or change the original`() {
        recovery.save(job.id, job.account.id, job.targetCharacterId, CharacterDeepSyncCheckpoint(original))

        assertFailsWith<IllegalArgumentException> { recovery.load(job.id, job.account.id + 1000, job.targetCharacterId) }
        assertFailsWith<IllegalStateException> { recovery.load(job.id, job.account.id, job.targetCharacterId + 1000) }
        assertEquals(original, assertNotNull(recovery.load(job.id, job.account.id, job.targetCharacterId)).original)
    }

    @Test
    fun `deleting a finished job also removes its recovery rows`() {
        recovery.save(job.id, job.account.id, job.targetCharacterId,
            CharacterDeepSyncCheckpoint(original, status = CharacterRecoveryStatus.RESTORED, collectionComplete = true))

        jobs.delete(job)

        assertFailsWith<IllegalArgumentException> { recovery.load(job.id, job.account.id, job.targetCharacterId) }
    }
}
