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
import org.junit.jupiter.api.Test
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

    @ParameterizedTest
    @ValueSource(strings = ["remove_all", "equip_item", "Equip_S_1", "Equip_L_1", "Equip_S_2", "Equip_L_2"])
    fun `임시 장비 변경 중 프로세스가 종료되어도 두 장비 저장과 선택한 최종 설정으로 마무리한다`(submission: String) {
        assertEquals(71, runProcess("equipment:$submission"))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertTrue(submission in interrupted.posts.last())
        assertEquals(submission != "remove_all", interrupted.equipment)
        if (submission != "remove_all") {
            assertEquals(if (submission.endsWith("_2")) "Guard Ring" else "Focus Ring", interrupted.equipmentName)
        }

        assertEquals(0, runProcess("resume"))
        val resumed = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(mapOf<Int, String?>(1 to "Focus Ring", 2 to "Guard Ring"), resumed.equipmentSlots)
        assertEquals(false, resumed.equipment)
        assertEquals(setting("1"), resumed.current)
        assertTrue(resumed.posts.all { it["character"] == "transfer-target" })
        assertTrue(resumed.posts.any { it["item_no"] == "ring-restarted" })
    }

    @Test
    fun `선택한 현재 패턴이 제외된 작업도 저장 직후 종료와 재시작 뒤 최초 대상 패턴을 보존한다`() {
        assertEquals(71, runProcess("unavailable-pattern:savepattern"))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(setting("2"), interrupted.current)
        assertEquals(setting("2"), interrupted.slots["0"])

        assertEquals(0, runProcess("resume"))
        val resumed = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(setting("0"), resumed.current)
        assertEquals(setting("2"), resumed.slots["0"])
        assertTrue(resumed.posts.all { it["character"] == "transfer-target" })
    }

    @Test
    fun `빈 장비 저장 검증의 임시 장착 중 종료되어도 최종 장비로 남기지 않는다`() {
        assertEquals(71, runProcess("equipment:empty-probe"))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(true, interrupted.equipment)
        assertEquals("Focus Ring", interrupted.equipmentName)
        assertTrue(2 in interrupted.equipmentSlots && interrupted.equipmentSlots[2] == null)

        assertEquals(0, runProcess("resume"))
        val resumed = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(mapOf<Int, String?>(1 to "Focus Ring", 2 to null), resumed.equipmentSlots)
        assertEquals(false, resumed.equipment)
        assertEquals(setting("1"), resumed.current)
        assertTrue(resumed.posts.all { it["character"] == "transfer-target" })
    }

    @Test
    fun `원본 현재 장비가 제외된 작업도 재시작 뒤 최초 대상 장비를 보존한다`() {
        assertEquals(71, runProcess("equipment:unavailable-current"))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals("Focus Ring", interrupted.equipmentName)

        assertEquals(0, runProcess("resume"))
        val resumed = mapper.readValue(directory.resolve("hof.json").readText(), TransferProcessState::class.java)
        assertEquals(mapOf<Int, String?>(1 to "Focus Ring", 2 to null), resumed.equipmentSlots)
        assertEquals(true, resumed.equipment)
        assertEquals("Guard Ring", resumed.equipmentName)
        assertEquals(setting("1").copy(rows = setting("1").rows + setting("0").rows), resumed.current)
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
            // 전체 Spring/JPA 기동에 50초가 걸릴 수 있다. 아래 작업 자체의 20초 제한과 구분한다.
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child timed out: ${output.readText().takeLast(8000)}")
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
    var equipment: Boolean? = null,
    var equipmentName: String = "Guard Ring",
    val equipmentSlots: MutableMap<Int, String?> = mutableMapOf(),
    var equipmentCandidateValue: String = "ring",
    val emptySecondPreset: Boolean = false,
    val unavailableCurrentEquipment: Boolean = false,
    val unavailableCurrentPattern: Boolean = false,
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
        stopAfter = args[1].substringAfter(":")
        state = if (stopAfter == "resume") {
            mapper.readValue(statePath.readText(), TransferProcessState::class.java).also {
                if (it.equipment != null) it.equipmentCandidateValue = "ring-restarted"
            }
        } else if (args[1].startsWith("equipment:")) {
            TransferProcessState(current = setting("0").copy(rows = setting("0").rows + setting("0").rows),
                equipment = true, emptySecondPreset = stopAfter in setOf("empty-probe", "unavailable-current"),
                unavailableCurrentEquipment = stopAfter == "unavailable-current")
        } else TransferProcessState(unavailableCurrentPattern = args[1].startsWith("unavailable-pattern:"))
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
                val sourceEquipment = if (state.equipment != null) state.unavailableCurrentEquipment else null
                snapshots.writeParsed(account.id, source.hofCharacterId,
                    parser.parsePage(source.hofCharacterId, page(source.hofCharacterId,
                        setting(if (state.unavailableCurrentPattern) "9" else "1"), mapOf("0" to setting("2")),
                        equipment = sourceEquipment,
                        skills = if (state.unavailableCurrentPattern) 0..9 else if (sourceEquipment == false) 0..1 else 0..2,
                        equipmentName = if (state.unavailableCurrentEquipment) "Unavailable Ring" else "Focus Ring")))
                val archive = context.getBean(CharacterSnapshotArchiveWriter::class.java)
                archive.savePatternSlot(source, "0",
                    parser.parsePage(source.hofCharacterId, page(source.hofCharacterId, setting("2"), mapOf("0" to setting("2")))))
                if (state.equipment != null) {
                    for ((slot, name) in mapOf(1 to "Focus Ring", 2 to "Guard Ring")) {
                        archive.saveEquipmentPreset(source, slot, parser.parsePage(source.hofCharacterId,
                            page(source.hofCharacterId, setting("1"), mapOf("0" to setting("2")),
                                equipment = !(slot == 2 && state.emptySecondPreset), equipmentName = name)), now)
                    }
                }
                snapshots.writeParsed(account.id, target.hofCharacterId,
                    parser.parsePage(target.hofCharacterId, page(target.hofCharacterId, state.current, state.slots,
                        state.equipment, equipmentName = state.equipmentName)))
                jobs.startTransfer(account.id, CharacterTransferExecuteRequest(source.id, target.id,
                    if (state.equipment != null) CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)
                    else CharacterTransferRequest(includeCurrentPattern = state.unavailableCurrentPattern,
                        savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
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
                        "ChangePattern" in fields -> state.current = state.current.copy(rows = state.current.rows.indices.map { index ->
                            CharacterPatternRowValue(fields.getValue("judge$index"), fields.getValue("quantity$index"), fields.getValue("skill$index")) })
                        "ChangePosition" in fields -> state.current = state.current.copy(position = fields.getValue("position"), guard = fields.getValue("guard"))
                        "savepattern" in fields -> state.slots[fields.getValue("patternno")] = state.current
                        "delpattern" in fields -> state.slots[fields.getValue("patternno")] = null
                        "remove_all" in fields -> {
                            state.equipment = false
                            state.current = state.current.copy(rows = state.current.rows.take(1).map {
                                if (it.skill == "2") it.copy(skill = "0") else it
                            })
                        }
                        "equip_item" in fields -> {
                            state.equipmentName = when (fields["item_no"]) {
                                state.equipmentCandidateValue -> "Focus Ring"
                                "guard-ring" -> "Guard Ring"
                                else -> error("현재 후보가 아닌 장비를 제출했습니다.")
                            }
                            state.equipment = true
                            state.current = state.current.copy(rows = state.current.rows.take(2) +
                                List((2 - state.current.rows.size).coerceAtLeast(0)) { setting("0").rows.single() })
                        }
                        "Equip_S_1" in fields || "Equip_S_2" in fields ->
                            state.equipmentSlots[if ("Equip_S_1" in fields) 1 else 2] = state.equipmentName.takeIf { state.equipment == true }
                        "Equip_L_1" in fields || "Equip_L_2" in fields -> {
                            val name = state.equipmentSlots[if ("Equip_L_1" in fields) 1 else 2]
                            state.equipment = name != null
                            state.equipmentName = name.orEmpty()
                            state.current = state.current.copy(rows = if (name == null) state.current.rows.take(1)
                                else state.current.rows.take(2) + List((2 - state.current.rows.size).coerceAtLeast(0)) { setting("0").rows.single() })
                        }
                        else -> error("알 수 없는 제출: ${fields.keys}")
                    }
                    statePath.writeText(mapper.writeValueAsString(state))
                    if (stopAfter in fields || (stopAfter in setOf("empty-probe", "unavailable-current") &&
                            "equip_item" in fields && state.posts.any { "Equip_S_2" in it })) {
                        Runtime.getRuntime().halt(71)
                    }
                }
                return HofHttpResponse(200, request.url, page(character, state.current, state.slots, state.equipment,
                    equipmentCandidateValue = state.equipmentCandidateValue, equipmentName = state.equipmentName), emptyMap())
            }
        }
    }
}
