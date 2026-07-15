package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

class UnifiedAutomationTypedLifecycleBridgeIntegrationTest {
    @Test
    fun `typed stopped resumes after commit while legacy running remains running and rollback emits nothing`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login", "encrypted", now)
        val profile = AutomationProfileEntity(8, account, "unified", "UNIFIED", true, now, now)
        val job = AutomationJobEntity(9, account, profile, "RUNNING", 0, null, now, now, now, null)
        val typedState = TypedAutomationRuntimeStateEntity(7, account, TypedAutomationLifecycle.STOPPED, AutomationStopReason.NETWORK.name, createdAt = now, updatedAt = now)
        val legacyQuery = Mockito.mock(UnifiedAutomationQueryRepository::class.java)
        val typedQuery = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val typedRuntime = Mockito.mock(TypedAutomationRuntimeService::class.java)
        Mockito.`when`(typedQuery.findRuntimeState(7)).thenReturn(typedState)
        Mockito.`when`(legacyQuery.findCurrentJob(7)).thenReturn(job)
        Mockito.`when`(legacyQuery.findProfile(7)).thenReturn(profile)
        Mockito.`when`(legacyQuery.findModules(8)).thenReturn(emptyList())
        val service = UnifiedAutomationService(
            Mockito.mock(AccountQueryRepository::class.java), Mockito.mock(AutomationProfileRepository::class.java),
            Mockito.mock(AutomationModuleConfigRepository::class.java), Mockito.mock(AutomationModuleMapCommandRepository::class.java),
            Mockito.mock(AutomationModuleQuestCommandRepository::class.java), Mockito.mock(AutomationModuleQuestMapCommandRepository::class.java),
            Mockito.mock(AutomationJobRepository::class.java), legacyQuery, Mockito.mock(BattleMapQueryRepository::class.java),
            Mockito.mock(PartyPresetQueryRepository::class.java), TimeProvider { now },
            Mockito.mock(AutomationAfterCommitWakeupService::class.java),
            AutomationModuleReadinessEvaluator(Mockito.mock(PartyPresetQueryRepository::class.java)), typedRuntime, typedQuery,
        )
        val database = DriverManagerDataSource("jdbc:h2:mem:lifecycle_${System.nanoTime()};DB_CLOSE_DELAY=-1", "sa", "")
        val transactions = TransactionTemplate(DataSourceTransactionManager(database))
        try {
            transactions.executeWithoutResult {
                assertEquals("RUNNING", service.resume(7).job?.status)
                Mockito.verifyNoInteractions(typedRuntime)
            }
            Mockito.verify(typedRuntime, Mockito.times(1)).resume(7)

            transactions.executeWithoutResult { status ->
                service.resume(7)
                status.setRollbackOnly()
            }
            Mockito.verify(typedRuntime, Mockito.times(1)).resume(7)
        } finally {
            database.connection.use { it.createStatement().execute("shutdown") }
        }
    }
}
