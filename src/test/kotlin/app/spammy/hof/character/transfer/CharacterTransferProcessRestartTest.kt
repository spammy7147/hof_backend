package app.spammy.hof.character.transfer

import app.spammy.hof.HofApplication
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.service.CharacterOperationJobService
import app.spammy.hof.character.service.CharacterSnapshotArchiveWriter
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.character.transfer.CharacterTransferFixture.page
import app.spammy.hof.character.transfer.CharacterTransferFixture.setting
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterDetailParser
import jakarta.persistence.EntityManager
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 별도 JVM과 파일 DB를 사용하며 HOF만 상태형 adapter로 대체한다. */
class CharacterTransferProcessRestartTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(strings = ["ChangePattern", "savepattern"])
    fun `임시 패턴 또는 저장 슬롯 제출 직후 프로세스가 종료되어도 최초 대상 패턴으로 마무리한다`(submission: String) {
        assertEquals(71, runProcess(submission))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(setting("2"), interrupted.current)
        assertEquals(if (submission == "savepattern") setting("2") else null, interrupted.slots["0"])

        assertEquals(0, runProcess("resume"))
        val resumed = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(setting("0"), resumed.current)
        assertEquals(setting("2"), resumed.slots["0"])
        assertTrue(resumed.posts.all { it["character"] == "transfer-target" })
    }

    private fun runProcess(mode: String): Int {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
            .plus(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("$mode.log").toFile()
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
            "-cp", classpath, CharacterTransferCrashProcess::class.java.name, directory.toString(), mode)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(55, TimeUnit.SECONDS), "child timed out: ${output.readText().takeLast(8000)}")
            return process.exitValue().also { assertTrue(it in setOf(0, 71), output.readText().takeLast(10000)) }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

internal data class TransferProcessState(
    var current: CharacterPatternSetting = setting("0"),
    val slots: MutableMap<String, CharacterPatternSetting?> = mutableMapOf("0" to null, "1" to null),
    val posts: MutableList<Map<String, String>> = mutableListOf(),
)

object CharacterTransferCrashProcess {
    private lateinit var statePath: Path
    private lateinit var state: TransferProcessState
    private lateinit var stopAfter: String
    private val mapper = jacksonObjectMapper()

    @JvmStatic
    fun main(args: Array<String>) {
        val directory = Path.of(args[0])
        statePath = directory.resolve("hof.json")
        stopAfter = args[1]
        state = if (stopAfter == "resume") mapper.readValue(statePath.readText(), TransferProcessState::class.java)
        else TransferProcessState()
        SpringApplicationBuilder(HofApplication::class.java, Remote::class.java).profiles("test").run(
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:file:${directory.resolve("database")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0",
        ).use { context ->
            val jobs = context.getBean(CharacterOperationJobService::class.java)
            if (stopAfter != "resume") {
                val em = context.getBean(EntityManager::class.java)
                val now = Instant.now()
                lateinit var account: HofAccountEntity
                lateinit var source: CharacterEntity
                lateinit var target: CharacterEntity
                TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                    account = HofAccountEntity(loginId = "transfer-process", encryptedPassword = "fixture", createdAt = now)
                    em.persist(account)
                    em.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = now))
                    source = CharacterEntity(account = account, hofCharacterId = "transfer-source", name = "원본", job = "Knight", updatedAt = now)
                    target = CharacterEntity(account = account, hofCharacterId = "transfer-target", name = "대상", job = "Knight", updatedAt = now)
                    em.persist(source)
                    em.persist(target)
                }
                val parser = context.getBean(CharacterDetailParser::class.java)
                val snapshots = context.getBean(CharacterSnapshotSynchronizer::class.java)
                snapshots.writeParsed(account.id, source.hofCharacterId,
                    parser.parsePage(source.hofCharacterId, page(source.hofCharacterId, setting("1"), mapOf("0" to setting("2")))))
                context.getBean(CharacterSnapshotArchiveWriter::class.java).savePatternSlot(source, "0",
                    parser.parsePage(source.hofCharacterId, page(source.hofCharacterId, setting("2"), mapOf("0" to setting("2")))))
                snapshots.writeParsed(account.id, target.hofCharacterId,
                    parser.parsePage(target.hofCharacterId, page(target.hofCharacterId, state.current, state.slots)))
                jobs.startTransfer(account.id, CharacterTransferExecuteRequest(source.id, target.id,
                    CharacterTransferRequest(savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
            }
            // resume는 ApplicationReadyEvent의 실제 startup 진입점이 소비한다.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var result = jobs.find(1L, 1L)
            while (result.status in setOf(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING) && System.nanoTime() < deadline) {
                Thread.sleep(50)
                result = jobs.find(1L, 1L)
            }
            check(result.status == CharacterOperationStatus.COMPLETED) { "${result.status}: ${result.message}" }
            check(result.transfer!!.results.all { it.status == CharacterTransferStepStatus.COMPLETED }) { result.transfer.toString() }
            val job = context.getBean(CharacterOperationJobQueryRepository::class.java).findById(1L)!!
            directory.resolve("checkpoint.json").writeText(job.requestPayload!!)
        }
    }

    @TestConfiguration
    class Remote {
        @Bean @Primary
        fun transferProcessGateway(): HofGateway = object : HofGateway {
            override fun execute(accountId: Long, request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
                val character = request.url.substringAfter("char=")
                check(character == "transfer-target") { "원본 또는 다른 대상에 요청했습니다: $character" }
                if (request.method == HofHttpMethod.POST) {
                    val fields = request.formFields
                    state.posts += fields + ("character" to character)
                    when {
                        "ChangePattern" in fields -> state.current = state.current.copy(rows = listOf(
                            CharacterPatternRowValue(fields.getValue("judge0"), fields.getValue("quantity0"), fields.getValue("skill0"))))
                        "ChangePosition" in fields -> state.current = state.current.copy(position = fields.getValue("position"), guard = fields.getValue("guard"))
                        "savepattern" in fields -> state.slots[fields.getValue("patternno")] = state.current
                        "delpattern" in fields -> state.slots[fields.getValue("patternno")] = null
                        else -> error("알 수 없는 제출: ${fields.keys}")
                    }
                    statePath.writeText(mapper.writeValueAsString(state))
                    if (stopAfter in fields) Runtime.getRuntime().halt(71)
                }
                return HofHttpResponse(200, request.url, page(character, state.current, state.slots), emptyMap())
            }
        }
    }
}
