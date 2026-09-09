package app.spammy.hof.character.transfer

import app.spammy.hof.character.transfer.CharacterTransferFixture.page
import app.spammy.hof.character.transfer.CharacterTransferFixture.setting
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.dto.CharacterTransferExecuteRequest
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.service.CharacterOperationJobService
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.pattern.CharacterPatternSetting
import app.spammy.hof.character.pattern.CharacterPatternDraft
import app.spammy.hof.character.pattern.CharacterPatternOperationResult
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.character.service.CharacterSnapshotArchiveWriter
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterDetailParser
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.core.task.TaskExecutor

/** 사용자 선택부터 실제 HOF 현재 설정과 저장 슬롯까지 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class CharacterTransferStateIntegrationTest {
    @Autowired private lateinit var transfers: CharacterTransferService
    @Autowired private lateinit var patterns: CharacterPatternService
    @Autowired private lateinit var snapshots: CharacterSnapshotSynchronizer
    @Autowired private lateinit var archive: CharacterSnapshotArchiveWriter
    @Autowired private lateinit var parser: CharacterDetailParser
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jobs: CharacterOperationJobService
    @Autowired private lateinit var jobQueries: CharacterOperationJobQueryRepository
    @Autowired private lateinit var jobCommands: CharacterOperationJobCommandRepository
    @MockitoBean private lateinit var hof: HofGateway
    @MockitoBean(name = "characterSyncTaskExecutor") private lateinit var taskExecutor: TaskExecutor

    private lateinit var source: CharacterEntity
    private lateinit var target: CharacterEntity
    private var accountId = 0L
    private lateinit var targetRevision: Instant
    private val sourceCurrent = setting("1")
    private val sourceSaved = setting("2")
    private var targetCurrent = setting("0")
    private val targetSlots = mutableMapOf<String, CharacterPatternSetting?>("0" to null, "1" to null)
    private val submittedCharacters = mutableListOf<String>()
    private var rejectedSaveSlot: String? = null
    private var rejectedCurrentSkill: String? = null
    private var observationVariant: String? = null
    private val tasks = ArrayDeque<Runnable>()

    @BeforeEach
    fun prepare() {
        Mockito.doAnswer { invocation -> tasks.addLast(invocation.getArgument(0)); null }
            .`when`(taskExecutor).execute(Mockito.any(Runnable::class.java))
        val now = Instant.now()
        TransactionTemplate(transactions).executeWithoutResult {
            val account = HofAccountEntity(loginId = "transfer-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = now)
            entityManager.persist(account)
            accountId = account.id
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = now))
            source = CharacterEntity(account = account, hofCharacterId = "transfer-source", name = "원본", job = "Knight", updatedAt = now)
            target = CharacterEntity(account = account, hofCharacterId = "transfer-target", name = "대상", job = "Knight", updatedAt = now)
            entityManager.persist(source)
            entityManager.persist(target)
        }
        snapshots.writeParsed(accountId, source.hofCharacterId,
            parser.parsePage(source.hofCharacterId, page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved))))
        archive.savePatternSlot(source, "0", parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceSaved, mapOf("0" to sourceSaved))))
        targetRevision = snapshots.writeParsed(accountId, target.hofCharacterId,
            parser.parsePage(target.hofCharacterId, page(target.hofCharacterId, targetCurrent, targetSlots))).revision
        Mockito.`when`(hof.execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap<String, String>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                val characterId = request.url.substringAfter("char=")
                check(characterId == target.hofCharacterId) { "설정 가져오기는 원본에 HOF 요청을 보내지 않는다." }
                if (request.method == HofHttpMethod.POST) {
                    submittedCharacters += characterId
                    val fields = request.formFields
                    if (("savepattern" in fields && fields["patternno"] == rejectedSaveSlot) ||
                        ("ChangePattern" in fields && fields["skill0"] == rejectedCurrentSkill)) {
                        return@thenAnswer HofHttpResponse(200, request.url,
                            page(target.hofCharacterId, targetCurrent, targetSlots) + "<div class='error'>저장을 거부했습니다.</div>", emptyMap())
                    }
                    when {
                        "ChangePattern" in fields -> targetCurrent = targetCurrent.copy(rows = listOf(CharacterPatternRowValue(
                            fields.getValue("judge0"), fields.getValue("quantity0"), fields.getValue("skill0"))))
                        "ChangePosition" in fields -> targetCurrent = targetCurrent.copy(
                            position = fields.getValue("position"), guard = fields.getValue("guard"))
                        "savepattern" in fields -> targetSlots[fields.getValue("patternno")] = targetCurrent
                        "delpattern" in fields -> targetSlots[fields.getValue("patternno")] = null
                        else -> error("예상하지 않은 설정 변경: ${fields.keys}")
                    }
                }
                val body = page(target.hofCharacterId, targetCurrent, targetSlots)
                val observed = when (observationVariant) {
                    "MISSING_QUANTITY" -> body.replace(Regex("<input[^>]*name=\"quantity0\"[^>]*>"), "")
                    "ERROR" -> body + "<div class='error'>캐릭터 관측을 완료하지 못했습니다.</div>"
                    else -> body
                }
                HofHttpResponse(200,
                    if (observationVariant == "OTHER_CHARACTER") request.url.replace("transfer-target", "other-character") else request.url,
                    observed, emptyMap())
            }
    }

    @Test
    fun `원격 설정이 같으면 저장 직전 재조회로 revision이 달라져도 가져오기를 허용한다`() {
        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true)))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(sourceCurrent, targetCurrent)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROWS", "POSITION", "GUARD"])
    fun `원격 패턴 위치 호위의 실제 변경은 초안으로 덮어쓰지 않는다`(changed: String) {
        val base = targetCurrent
        targetCurrent = when (changed) {
            "ROWS" -> sourceSaved
            "POSITION" -> base.copy(position = "back")
            else -> base.copy(guard = "always")
        }
        val remoteBefore = targetCurrent

        val result = patterns.applyDraft(accountId, target.id, base, targetRevision,
            CharacterPatternDraft(targetRevision, sourceCurrent.rows, sourceCurrent.position, sourceCurrent.guard))

        assertIs<CharacterPatternOperationResult.Conflict>(result)
        assertEquals(remoteBefore, targetCurrent)
        assertTrue(submittedCharacters.isEmpty())
    }

    @Test
    fun `현재 A와 저장 B를 함께 가져온 뒤 현재는 A이고 저장 슬롯은 B다`() {
        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(sourceSaved, targetSlots["0"])
        assertEquals(sourceCurrent, targetCurrent)
        assertTrue(submittedCharacters.isNotEmpty())
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `저장 패턴 두 개만 가져오면 현재 패턴은 대상의 원래 설정을 유지한다`() {
        val originalCurrent = targetCurrent
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved, "1" to sourceCurrent))))
        archive.savePatternSlot(source, "1", parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("1" to sourceCurrent))))

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(savedPatternMappings = listOf(
                CharacterSavedPatternMapping("0", "0"), CharacterSavedPatternMapping("1", "1")))))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(sourceSaved, targetSlots["0"])
        assertEquals(sourceCurrent, targetSlots["1"])
        assertEquals(originalCurrent, targetCurrent, "저장 슬롯 복사용 임시 패턴을 현재 설정으로 남기지 않는다.")
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `DB checkpoint에서 재개할 때 임시 패턴을 원래 현재 패턴으로 다시 보존하지 않는다`(includeCurrent: Boolean) {
        val intendedCurrent = if (includeCurrent) sourceCurrent else targetCurrent
        rejectedCurrentSkill = intendedCurrent.rows.single().skill
        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = includeCurrent,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
        tasks.removeFirst().run()
        assertEquals(sourceSaved, targetCurrent)
        assertEquals(sourceSaved, targetSlots["0"])
        assertEquals(CharacterTransferStepStatus.FAILED, jobs.find(accountId, started.id).transfer!!.results.last().status)

        // 별도 프로세스 종료 검증과 구분해, 저장된 단계가 있는 작업의 startup 재진입을 재현한다.
        val interrupted = jobQueries.findByAccountIdAndId(accountId, started.id)!!
        interrupted.status = CharacterOperationStatus.RUNNING
        interrupted.resultPayload = null
        interrupted.finishedAt = null
        jobCommands.save(interrupted)
        rejectedCurrentSkill = null
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceSaved, mapOf("0" to sourceSaved))))
        jobs.resumeIncompleteJobs()
        tasks.removeFirst().run()

        val resumed = jobs.find(accountId, started.id)
        assertEquals(CharacterOperationStatus.COMPLETED, resumed.status)
        assertTrue(resumed.transfer!!.results.all { it.status == CharacterTransferStepStatus.COMPLETED })
        assertEquals(intendedCurrent, targetCurrent, "재시작 후 다른 원본 관측이 들어와도 최초 복사 원본을 유지한다.")
        assertEquals(sourceSaved, targetSlots["0"])
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `이미 최종 패턴인 완료 checkpoint는 재관측만 하고 다시 제출하지 않는다`() {
        targetCurrent = sourceCurrent
        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true)), setOf("current-pattern"))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED })
        assertEquals(sourceCurrent, targetCurrent)
        assertTrue(submittedCharacters.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["OTHER_CHARACTER", "MISSING_QUANTITY", "ERROR"])
    fun `다른 캐릭터나 불완전한 관측을 원래 설정으로 보존하거나 적용하지 않는다`(variant: String) {
        observationVariant = variant
        val originalCurrent = targetCurrent

        assertFailsWith<IllegalStateException> {
            transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
                CharacterTransferRequest(savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
        }

        assertEquals(originalCurrent, targetCurrent)
        assertTrue(submittedCharacters.isEmpty())
    }

    @Test
    fun `완료된 최종 패턴 단계도 재개 시 실제 현재 설정을 다시 확인한다`() {
        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true)), setOf("current-pattern"))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(sourceCurrent, targetCurrent, "완료 checkpoint만으로 현재 설정을 확인했다고 간주하지 않는다.")
    }

    @Test
    fun `저장 패턴의 저장이 거부되어도 임시 패턴을 대상의 현재 설정으로 남기지 않는다`() {
        val originalCurrent = targetCurrent
        rejectedSaveSlot = "0"

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))

        assertEquals(CharacterTransferStepStatus.FAILED, result.results.first().status)
        assertEquals(CharacterTransferStepStatus.COMPLETED, result.results.last().status)
        assertEquals(originalCurrent, targetCurrent)
        assertEquals(null, targetSlots["0"])
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, "https://example.test")
}
