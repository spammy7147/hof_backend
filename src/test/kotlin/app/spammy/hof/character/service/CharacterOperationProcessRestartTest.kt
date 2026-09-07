package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.identity.CharacterLifecycleTransaction
import app.spammy.hof.town.common.service.AccountHofMutationFence
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.*
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.automation.service.UnifiedAutomationService
import app.spammy.hof.automation.service.AutomationStopReason
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.character.command.TypedAutomationCharacterCommandBridge
import app.spammy.hof.character.command.ThreadSleepCharacterCommandPauseWaiter
import app.spammy.hof.character.repository.*
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import jakarta.persistence.EntityManagerFactory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.core.task.TaskExecutor
import app.spammy.hof.character.config.CharacterSyncTaskExecutorConfig
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 실제 작업·원본 DB·archive·parser/명령을 유지하고 외부 HOF와 자동화 gate만 fixture로 대체한다. */
class CharacterOperationProcessRestartTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `job resumes after a slot submission kills the process with its original database intact`() = verifyRestart("loadpattern")

    @Test
    fun `job resumes after original equipment was reattached without replaying completed restoration`() = verifyRestart("equip_item")

    @Test
    fun `lost pattern restoration response does not resubmit equipment or pattern`() = verifyRestart("ChangePattern")

    @Test
    fun `restored remote state survives termination before the completion record with no further posts`() = verifyRestart("ChangePosition")

    @Test
    fun `archived character restore uses the same persisted original across a process restart`() = verifyRestart("restore:loadpattern")

    @Test
    fun `manual stop between processes prevents automatic resume after the original is restored`() =
        verifyRestart("loadpattern", "resume-after-stop", "STOPPED")

    @Test
    fun `authentication suspension between processes prevents automatic resume after restoration`() =
        verifyRestart("loadpattern", "resume-after-auth", "PAUSED")

    @Test
    fun `explicit retry after the recovery budget is exhausted restores the same original without collecting again`() {
        verifyRestart("ChangePattern", "resume-with-manual-retry")
        val result = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals(4, result["restoreAttempts"].asInt())
        assertEquals(6, result["restoreAttemptLimit"].asInt())
    }

    private fun verifyRestart(termination: String, resumeMode: String = "resume", expectedLifecycle: String = "RUNNING") {
        assertEquals(71, runProcess(termination))
        val beforePosts = directory.resolve("posts.jsonl").toFile().readLines().size
        assertEquals(0, runProcess(resumeMode))
        val result = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("COMPLETED", result["status"].asString())
        assertEquals("RESTORED", result["recoveryStatus"].asString())
        assertEquals("9564", result["originalSkill"].asString())
        assertTrue(result["automationReleased"].asBoolean(), "persisted automation pause must be resolved after restoration")
        assertEquals(expectedLifecycle, result["automationLifecycle"].asString())
        val remote = jacksonObjectMapper().readTree(directory.resolve("hof.json").readText())
        assertTrue(remote["equipped"].asBoolean())
        assertEquals("9564", remote["skill"].asString())
        assertEquals("front", remote["position"].asString())
        assertEquals("1", remote["guard"].asString())
        val resumed = directory.resolve("posts.jsonl").toFile().readLines().drop(beforePosts)
            .map { jacksonObjectMapper().readTree(it) }
        when (termination) {
            "equip_item" -> assertTrue(resumed.none { it.has("remove_all") || it.has("equip_item") })
            "ChangePattern" -> assertTrue(resumed.none { it.has("remove_all") || it.has("equip_item") || it.has("ChangePattern") })
            "ChangePosition" -> assertTrue(resumed.isEmpty(), "already restored state must not be submitted again")
        }
    }

    private fun runProcess(mode: String): Int {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
            .plus(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("$mode.log").toFile()
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
            "-cp", classpath, CharacterOperationCrashProcess::class.java.name, directory.toString(), mode,
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "child process timed out: ${output.readText().takeLast(6000)}")
            return process.exitValue().also { code ->
                assertTrue(code in setOf(0, 71), "child process failed: ${output.readText().takeLast(10000)}")
            }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

object CharacterOperationCrashProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        val source = DriverManagerDataSource("jdbc:h2:file:${directory.resolve("database")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0", "sa", "")
        Flyway.configure().dataSource(source).load().migrate()
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean("dataSource", DataSource::class.java, java.util.function.Supplier { source })
            context.register(Persistence::class.java)
            context.refresh()
            val accounts = context.getBean(HofAccountRepository::class.java)
            val accountQuery = context.getBean(AccountQueryRepository::class.java)
            val characterQuery = context.getBean(CharacterQueryRepository::class.java)
            val commands = context.getBean(CharacterOperationJobCommandRepository::class.java)
            val queries = context.getBean(CharacterOperationJobQueryRepository::class.java)
            val recovery = context.getBean(CharacterDeepSyncRecovery::class.java)
            val now = Instant.parse("2026-09-07T00:00:00Z")
            val account = accountQuery.findByLoginId("crash-fixture") ?: accounts.save(HofAccountEntity(loginId = "crash-fixture", encryptedPassword = "fixture", createdAt = now))
            check(account.id == 1L)
            val character = characterQuery.findByAccountIdAndHofCharacterId(account.id, "10")
                ?: context.getBean(CharacterRepository::class.java).save(CharacterEntity(account = account, hofCharacterId = "10", name = "fixture", job = "Knight", updatedAt = now))
            val typed = context.getBean(TypedAutomationQueryRepository::class.java)
            if (typed.findRuntimeState(account.id) == null) {
                context.getBean(AutomationEntryCommandRepository::class.java).save(AutomationEntryEntity(account = account,
                    type = AutomationType.UNION, priority = 0, enabled = true, createdAt = now, updatedAt = now))
                context.getBean(TypedAutomationRuntimeStateCommandRepository::class.java).save(TypedAutomationRuntimeStateEntity(
                    account.id, account, TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
            }
            val automationGate = TypedAutomationCharacterCommandBridge(Mockito.mock(UnifiedAutomationService::class.java),
                ThreadSleepCharacterCommandPauseWaiter(), context.getBean(CharacterOperationAutomation::class.java),
                context.getBean(AccountHofMutationFence::class.java))
            val isResume = args[1].startsWith("resume")
            if (args[1] in setOf("resume-after-stop", "resume-after-auth")) {
                TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                    val automation = context.getBean(TypedAutomationLifecycleBridge::class.java)
                    if (args[1] == "resume-after-stop") automation.stop(account.id, AutomationStopReason.MANUAL_STOP, "USER_STOP")
                    else automation.suspendForAuthentication(account.id, "LAST_APP_SESSION_ENDED")
                }
            }
            val lifecycle = context.getBean(CharacterLifecycleService::class.java)
            if (args[1].startsWith("restore:")) lifecycle.archive(account.id, character.id)
            val remote = CharacterDeepSyncServiceTest.Fixture(
                characterId = character.id, recoveryOverride = recovery,
                archiveOverride = context.getBean(CharacterSnapshotArchiveWriter::class.java),
                lifecycleOverride = lifecycle,
                automationGateOverride = automationGate,
                beforePost = { check(typed.findRuntimeState(account.id)?.lifecycleStatus in setOf(TypedAutomationLifecycle.PAUSED, TypedAutomationLifecycle.STOPPED)) },
                stateFile = directory.resolve("hof.json"), terminateAfter = args[1].substringAfter(':').takeUnless { isResume },
                equipmentPatternRows = true,
            )
            context.registerBean(CharacterOperationJobService::class.java, java.util.function.Supplier {
                CharacterOperationJobService(accountQuery, characterQuery, commands, queries, remote.service,
                    Mockito.mock(CharacterTransferService::class.java), HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java)),
                    jacksonObjectMapper(), TimeProvider { now }, context.getBean("characterSyncTaskExecutor", TaskExecutor::class.java),
                    context.getBean(CharacterOperationAutomation::class.java))
            })
            val jobs = context.getBean(CharacterOperationJobService::class.java)
            when {
                args[1] == "resume-with-manual-retry" -> {
                    val checkpoint = requireNotNull(recovery.load(1L, account.id, character.id))
                    recovery.save(1L, account.id, character.id, checkpoint.copy(restoreAttempts = 3))
                    val failed = requireNotNull(queries.findById(1L))
                    failed.status = CharacterOperationStatus.FAILED
                    failed.message = "원본 복구 시도 한도를 넘었습니다."
                    commands.save(failed)
                    jobs.retryRecovery(account.id, 1L)
                    jobs.retryRecovery(account.id, 1L)
                }
                isResume -> jobs.resumeIncompleteJobs()
                args[1].startsWith("restore:") -> jobs.startRestore(account.id, character.id)
                else -> jobs.startDeepSync(account.id, character.id)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var result = jobs.find(account.id, 1L)
            while (result.status in setOf(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING) && System.nanoTime() < deadline) {
                Thread.sleep(50)
                result = jobs.find(account.id, 1L)
            }
            check(result.status == CharacterOperationStatus.COMPLETED) { "Job failed or timed out: ${result.status} ${result.message}" }
            val checkpoint = requireNotNull(recovery.load(result.id, account.id, character.id))
            java.nio.file.Files.writeString(directory.resolve("result.json"), jacksonObjectMapper().writeValueAsString(mapOf(
                "status" to result.status, "recoveryStatus" to result.recoveryStatus,
                "originalSkill" to checkpoint.original.patterns.single { it.index == 0 }.skill, "message" to result.message,
                "automationReleased" to queries.findById(result.id)?.automationReleased,
                "automationLifecycle" to typed.findRuntimeState(account.id)?.lifecycleStatus,
                "restoreAttempts" to checkpoint.restoreAttempts, "restoreAttemptLimit" to checkpoint.restoreAttemptLimit,
            )))
            check(result.status == CharacterOperationStatus.COMPLETED) { "Job failed: ${result.message}" }
            check(result.recoveryStatus == CharacterRecoveryStatus.RESTORED)
            val restoredPatterns = characterQuery.findActionPatternsByCharacterIds(listOf(character.id)).sortedBy { it.rowIndex }
                .map { app.spammy.hof.external.model.HofActionPatternRow(it.rowIndex, judge = it.judge, quantity = it.quantity, skill = it.skill) }
            check(restoredPatterns == checkpoint.original.patterns)
            check(restoredPatterns.size == 3)
            check(characterQuery.findByAccountIdAndId(account.id, character.id)?.lifecycle == CharacterLifecycle.ACTIVE)
        }
    }

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = ["app.spammy.hof.account.repository", "app.spammy.hof.character.repository", "app.spammy.hof.automation"])
    @Import(CharacterSyncTaskExecutorConfig::class, QueryDslConfig::class, AccountQueryRepository::class, CharacterQueryRepository::class,
        CharacterOperationJobQueryRepository::class, CharacterRecoveryQueryRepository::class,
        CharacterDeepSyncRecovery::class, CharacterSnapshotWriter::class, CharacterSnapshotArchiveWriter::class,
        CharacterLifecycleService::class, CharacterLifecycleTransaction::class, CharacterIdentityQueryRepository::class,
        AccountHofMutationFence::class, CharacterOperationAutomation::class,
        TypedAutomationQueryRepository::class, TypedAutomationLifecycleBridge::class,
        AdventureDailyPreflightQueryRepository::class, AutomationWorkSessionQueryRepository::class,
        AutomationOutboxService::class, AutomationOutboxQueryRepository::class)
    class Persistence {
        @Bean fun timeProvider() = TimeProvider { Instant.parse("2026-09-07T00:00:00Z") }
        @Bean fun objectMapper() = jacksonObjectMapper()
        @Bean fun executionAuthorization() = Mockito.mock(AccountExecutionAuthorizationReader::class.java).also {
            Mockito.`when`(it.isExecutionAllowed(Mockito.anyLong())).thenReturn(true)
        }
        @Bean fun entityManagerFactory(dataSource: DataSource) = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
            setPackagesToScan("app.spammy.hof")
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "validate"))
        }
        @Bean fun transactionManager(factory: EntityManagerFactory) = JpaTransactionManager(factory)
    }
}
