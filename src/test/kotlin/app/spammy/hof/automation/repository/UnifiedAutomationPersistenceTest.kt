package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
class UnifiedAutomationPersistenceTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var profileRepository: AutomationProfileRepository
    @Autowired private lateinit var jobRepository: AutomationJobRepository
    @Autowired private lateinit var moduleConfigRepository: AutomationModuleConfigRepository
    @Autowired private lateinit var actionRunRepository: AutomationActionRunRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun persistsEnoughStateToResumeTheSameActionAfterARestart() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        val account = accountRepository.save(
            HofAccountEntity(loginId = "resume-account", encryptedPassword = "encrypted", createdAt = now),
        )
        val profile = profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = "통합 자동화",
                mode = "UNIFIED",
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        moduleConfigRepository.save(
            AutomationModuleConfigEntity(
                profile = profile,
                moduleType = AutomationModuleType.KEY_QUEST,
                enabled = true,
                priority = 0,
                settingsJson = "{\"questIds\":[\"0563\",\"0571\"]}",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val job = jobRepository.save(
            AutomationJobEntity(
                account = account,
                profile = profile,
                status = "RUNNING",
                currentStepIndex = 0,
                message = null,
                createdAt = now,
                startedAt = now,
                updatedAt = now,
                finishedAt = null,
                currentModule = AutomationModuleType.KEY_QUEST.name,
                currentAction = "BATTLE:Noble1021",
                nextRunAt = now.plusSeconds(5),
                lastHeartbeatAt = now,
            ),
        )
        actionRunRepository.save(
            AutomationActionRunEntity(
                job = job,
                moduleType = AutomationModuleType.KEY_QUEST,
                actionType = "BATTLE",
                actionKey = "Noble1021",
                status = AutomationActionStatus.RETRY_WAIT,
                requestKey = "job:${job.id}:step:0",
                payloadJson = "{\"mapCode\":\"Noble1021\"}",
                attemptCount = 1,
                nextAttemptAt = now.plusSeconds(5),
                lastError = "temporary failure",
                createdAt = now,
                updatedAt = now,
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val restoredJob = entityManager.find(AutomationJobEntity::class.java, job.id)
        val restoredConfig = entityManager.createQuery(
            "select c from AutomationModuleConfigEntity c where c.profile.id = :profileId",
            AutomationModuleConfigEntity::class.java,
        ).setParameter("profileId", profile.id).singleResult
        val restoredAction = entityManager.createQuery(
            "select a from AutomationActionRunEntity a where a.job.id = :jobId",
            AutomationActionRunEntity::class.java,
        ).setParameter("jobId", job.id).singleResult

        assertNotNull(restoredJob)
        assertEquals("BATTLE:Noble1021", restoredJob.currentAction)
        assertEquals(now.plusSeconds(5), restoredJob.nextRunAt)
        assertEquals(AutomationModuleType.KEY_QUEST, restoredConfig.moduleType)
        assertEquals(AutomationActionStatus.RETRY_WAIT, restoredAction.status)
        assertEquals("job:${job.id}:step:0", restoredAction.requestKey)
        assertEquals(1, restoredAction.attemptCount)
    }
}
