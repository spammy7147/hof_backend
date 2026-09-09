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
    private val submittedFields = mutableListOf<Map<String, String>>()
    private var rejectedSaveSlot: String? = null
    private var rejectedCurrentSkill: String? = null
    private var observationVariant: String? = null
    private var targetEquipment: Boolean? = null
    private var rejectEquipment = false
    private var ignoreEquipment = false
    private var ignoredEquipmentSave: Int? = null
    private var ignoreEquipmentLoad = false
    private var equipmentCapacity = 2
    private var equipmentGrantsSkill = true
    private var equipmentCandidateValue = "ring"
    private var targetEquipmentName = "Focus Ring"
    private val targetEquipmentSlots = mutableMapOf<Int, String?>()
    private var changeEquipmentCandidateAfterClear = false
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
                    submittedFields += fields
                    if (("savepattern" in fields && fields["patternno"] == rejectedSaveSlot) ||
                        ("ChangePattern" in fields && fields["skill0"] == rejectedCurrentSkill) ||
                        ("equip_item" in fields && rejectEquipment)) {
                        return@thenAnswer HofHttpResponse(200, request.url,
                            page(target.hofCharacterId, targetCurrent, targetSlots, targetEquipment) + "<div class='error'>저장을 거부했습니다.</div>", emptyMap())
                    }
                    when {
                        "ChangePattern" in fields -> targetCurrent = targetCurrent.copy(rows = targetCurrent.rows.indices.map { index ->
                            CharacterPatternRowValue(fields.getValue("judge$index"), fields.getValue("quantity$index"), fields.getValue("skill$index")) })
                        "ChangePosition" in fields -> targetCurrent = targetCurrent.copy(
                            position = fields.getValue("position"), guard = fields.getValue("guard"))
                        "savepattern" in fields -> targetSlots[fields.getValue("patternno")] = targetCurrent
                        "delpattern" in fields -> targetSlots[fields.getValue("patternno")] = null
                        "remove_all" in fields -> {
                            targetEquipment = false
                            if (changeEquipmentCandidateAfterClear) equipmentCandidateValue = "ring-after-clear"
                            targetCurrent = targetCurrent.copy(rows = targetCurrent.rows.take(1).map {
                                if (it.skill == "2") it.copy(skill = "0") else it
                            })
                        }
                        "equip_item" in fields -> {
                            val itemName = when (fields["item_no"]) {
                                equipmentCandidateValue -> "Focus Ring"
                                "guard-ring" -> "Guard Ring"
                                else -> error("관측하지 않은 장비를 장착했습니다.")
                            }
                            if (!ignoreEquipment) {
                                targetEquipment = true
                                targetEquipmentName = itemName
                                targetCurrent = targetCurrent.copy(rows = targetCurrent.rows.take(equipmentCapacity) +
                                    List((equipmentCapacity - targetCurrent.rows.size).coerceAtLeast(0)) { setting("0").rows.single() })
                            }
                        }
                        "Equip_S_1" in fields || "Equip_S_2" in fields -> {
                            val slot = if ("Equip_S_1" in fields) 1 else 2
                            if (slot != ignoredEquipmentSave) targetEquipmentSlots[slot] = targetEquipmentName.takeIf { targetEquipment == true }
                        }
                        "Equip_L_1" in fields || "Equip_L_2" in fields -> {
                            val name = targetEquipmentSlots[if ("Equip_L_1" in fields) 1 else 2]
                            if (!ignoreEquipmentLoad) {
                                targetEquipment = name != null
                                targetEquipmentName = name.orEmpty()
                                targetCurrent = targetCurrent.copy(rows = if (name == null) targetCurrent.rows.take(1)
                                    else targetCurrent.rows.take(2) + List((2 - targetCurrent.rows.size).coerceAtLeast(0)) { setting("0").rows.single() })
                            }
                        }
                        else -> error("예상하지 않은 설정 변경: ${fields.keys}")
                    }
                }
                val body = page(target.hofCharacterId, targetCurrent, targetSlots, targetEquipment,
                    skills = if (targetEquipment == false || !equipmentGrantsSkill) 0..1 else 0..2,
                    equipmentCandidateValue = equipmentCandidateValue, equipmentName = targetEquipmentName)
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

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `장비가 늘리는 용량과 스킬을 장착 후 확인해 현재 패턴을 가져온다`(sourceRows: Int) {
        targetEquipment = false
        val desired = CharacterPatternSetting((setting("2").rows + setting("1").rows).take(sourceRows), "back", "always")
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, desired, mapOf("0" to sourceSaved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(true, targetEquipment)
        val expectedRows = if (sourceRows == 1) listOf("2", "0") else listOf("2", "1")
        assertEquals(expectedRows, targetCurrent.rows.map { it.skill })
        assertEquals("back", targetCurrent.position)
        assertEquals("always", targetCurrent.guard)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNCHANGED", "BEFORE_RESUME", "AFTER_CLEAR"])
    fun `완료 장비 checkpoint도 재개 시 실제 현재 장비와 최신 후보 값을 다시 확인한다`(candidateChange: String) {
        targetEquipment = false
        val desired = setting("2").copy(position = "back", guard = "always")
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, desired, mapOf("0" to sourceSaved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))
        val selection = CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true))
        var snapshot: CharacterTransferSnapshot? = null
        val first = transfers.execute(accountId, selection, onSnapshot = { snapshot = it })
        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())

        // 완료 저장 뒤 HOF에서 현재 장비가 바뀌어도 과거 완료 ID를 최종 상태로 믿지 않는다.
        targetEquipment = false
        targetCurrent = setting("0")
        if (candidateChange == "BEFORE_RESUME") equipmentCandidateValue = "ring-new"
        changeEquipmentCandidateAfterClear = candidateChange == "AFTER_CLEAR"
        val resumed = transfers.execute(accountId, selection, first.results.map { it.stepId }.toSet(), snapshot)

        assertTrue(resumed.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, resumed.toString())
        assertEquals(true, targetEquipment)
        assertEquals(listOf("2", "0"), targetCurrent.rows.map { it.skill })
        assertEquals("back", targetCurrent.position)
        assertEquals("always", targetCurrent.guard)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `장비 식별 근거가 없는 기존 작업 기록으로 후보 값을 다시 제출하지 않는다`() {
        targetEquipment = false
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))
        val selection = CharacterTransferSelection(source.id, target.id, CharacterTransferRequest(includeEquipment = true))
        var snapshot: CharacterTransferSnapshot? = null
        val first = transfers.execute(accountId, selection, onSnapshot = { snapshot = it })
        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())
        val original = checkNotNull(snapshot)
        val legacy = original.copy(source = original.source.copy(equipment = original.source.equipment.map { it.copy(identity = null) }))
        submittedFields.clear()

        assertFailsWith<IllegalArgumentException> {
            transfers.execute(accountId, selection, first.results.map { it.stepId }.toSet(), legacy)
        }

        assertTrue(submittedFields.isEmpty(), "기존 후보 값만으로 해제·장착을 시작하지 않는다.")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `장비 저장의 성공 응답만으로 저장 결과를 완료 처리하지 않고 최종 현재 설정을 복원한다`(loadAlsoIgnored: Boolean) {
        targetEquipment = false
        ignoredEquipmentSave = 2
        ignoreEquipmentLoad = loadAlsoIgnored
        targetEquipmentSlots[2] = "Focus Ring"
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = false)))
        archive.saveEquipmentPreset(source, 2, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true, equipmentName = "Guard Ring")), Instant.now())
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeEquipment = true)))

        assertEquals(CharacterTransferStepStatus.FAILED, result.results.single { it.stepId == "equipment-preset:2:save" }.status)
        assertEquals("Focus Ring", targetEquipmentSlots[2])
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUBMISSIONS_IGNORED", "PROBE_IGNORED", "PROBE_REJECTED"])
    fun `빈 장비 저장도 불러오기 전후 변화가 없으면 완료로 오인하지 않는다`(outcome: String) {
        targetEquipment = false
        ignoredEquipmentSave = if (outcome == "SUBMISSIONS_IGNORED") 2 else null
        ignoreEquipmentLoad = outcome == "SUBMISSIONS_IGNORED"
        ignoreEquipment = outcome == "PROBE_IGNORED"
        rejectEquipment = outcome == "PROBE_REJECTED"
        targetEquipmentSlots[2] = "Guard Ring"
        val emptySource = parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = false))
        snapshots.writeParsed(accountId, source.hofCharacterId, emptySource)
        archive.saveEquipmentPreset(source, 2, emptySource, Instant.now())
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))
        val selection = CharacterTransferSelection(source.id, target.id, CharacterTransferRequest(includeEquipment = true))

        val failed = transfers.execute(accountId, selection)

        assertEquals(CharacterTransferStepStatus.FAILED, failed.results.single { it.stepId == "equipment-preset:2:save" }.status)
        assertEquals(if (outcome == "SUBMISSIONS_IGNORED") "Guard Ring" else null, targetEquipmentSlots[2])
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)

        ignoredEquipmentSave = null
        ignoreEquipmentLoad = false
        ignoreEquipment = false
        rejectEquipment = false
        val completed = transfers.execute(accountId, selection)

        assertTrue(completed.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, completed.toString())
        assertTrue(2 in targetEquipmentSlots && targetEquipmentSlots[2] == null)
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `원본 현재 장비를 제외해도 실패와 재진입 뒤 대상의 최초 장비를 보존한다`() {
        targetEquipment = true
        targetEquipmentName = "Guard Ring"
        val originalPattern = setting("0").copy(rows = setting("0").rows + setting("0").rows)
        targetCurrent = originalPattern
        ignoreEquipmentLoad = true
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true, equipmentName = "Unavailable Ring")))
        archive.saveEquipmentPreset(source, 2, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = false)), Instant.now())
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = true, equipmentName = "Guard Ring")))
        val selection = CharacterTransferSelection(source.id, target.id, CharacterTransferRequest(includeEquipment = true))
        var snapshot: CharacterTransferSnapshot? = null

        val failed = transfers.execute(accountId, selection, onSnapshot = { snapshot = it })

        assertEquals(CharacterTransferStepStatus.FAILED, failed.results.single { it.stepId == "equipment-preset:2:save" }.status)
        assertEquals(true, targetEquipment)
        assertEquals("Guard Ring", targetEquipmentName)
        assertEquals(originalPattern, targetCurrent)

        targetEquipmentName = "Focus Ring"
        ignoreEquipmentLoad = false
        val resumed = transfers.execute(accountId, selection,
            failed.results.filter { it.status == CharacterTransferStepStatus.COMPLETED }.map { it.stepId }.toSet(), snapshot)

        assertTrue(resumed.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, resumed.toString())
        assertTrue(2 in targetEquipmentSlots && targetEquipmentSlots[2] == null)
        assertEquals(true, targetEquipment)
        assertEquals("Guard Ring", targetEquipmentName)
        assertEquals(originalPattern, targetCurrent)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })

        submittedFields.clear()
        assertFailsWith<IllegalArgumentException> {
            transfers.execute(accountId, selection, snapshot = checkNotNull(snapshot).copy(originalEquipment = null))
        }
        assertTrue(submittedFields.isEmpty(), "최초 대상 장비가 없는 기존 기록은 임시 장비를 원래 장비로 취급하지 않는다.")
    }

    @Test
    fun `장비 저장 두 개의 임시 설정이 완료 기록 재개 뒤에도 최종 현재 장비로 남지 않는다`() {
        targetEquipment = false
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = false)))
        for ((slot, name) in mapOf(1 to "Focus Ring", 2 to "Guard Ring")) {
            archive.saveEquipmentPreset(source, slot, parser.parsePage(source.hofCharacterId,
                page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true, equipmentName = name)), Instant.now())
        }
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))
        val selection = CharacterTransferSelection(source.id, target.id, CharacterTransferRequest(includeEquipment = true))
        val preview = transfers.preview(accountId, selection)
        assertTrue(preview.issues.isEmpty(), preview.toString())
        var snapshot: CharacterTransferSnapshot? = null
        val first = transfers.execute(accountId, selection, onSnapshot = { snapshot = it })
        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())
        assertEquals(mapOf<Int, String?>(1 to "Focus Ring", 2 to "Guard Ring"), targetEquipmentSlots)
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)

        targetEquipment = true
        targetEquipmentName = "Guard Ring"
        targetEquipmentSlots[1] = "Guard Ring"
        targetEquipmentSlots[2] = "Focus Ring"
        val resumed = transfers.execute(accountId, selection, first.results.map { it.stepId }.toSet(), snapshot)

        assertTrue(resumed.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, resumed.toString())
        assertEquals(mapOf<Int, String?>(1 to "Focus Ring", 2 to "Guard Ring"), targetEquipmentSlots)
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `장비만 가져올 때 정상 응답이어도 실제 미장착이면 완료로 기록하지 않는다`() {
        targetEquipment = false
        ignoreEquipment = true
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeEquipment = true)))

        assertEquals(CharacterTransferStepStatus.FAILED, result.results.single { it.stepId == "equipment-current:item:0" }.status)
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `장비 적용 뒤 저장 패턴을 복사해도 대상의 기존 현재 행동 패턴을 유지한다`() {
        targetEquipment = false
        targetCurrent = setting("1").copy(position = "back", guard = "always")
        val saved = CharacterPatternSetting(setting("2").rows + setting("1").rows, "front", "never")
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to saved), equipment = true)))
        archive.savePatternSlot(source, "0", parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, saved, mapOf("0" to saved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeEquipment = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(saved, targetSlots["0"])
        assertEquals(listOf("1", "0"), targetCurrent.rows.map { it.skill })
        assertEquals("back", targetCurrent.position)
        assertEquals("always", targetCurrent.guard)
        assertEquals(true, targetEquipment)
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["EQUIPMENT_REJECTED", "CAPACITY_UNAVAILABLE", "SKILL_UNAVAILABLE"])
    fun `장비 적용 후에도 호환되지 않는 패턴은 자르거나 제출하지 않는다`(outcome: String) {
        targetEquipment = false
        rejectEquipment = outcome == "EQUIPMENT_REJECTED"
        equipmentCapacity = if (outcome == "CAPACITY_UNAVAILABLE") 1 else 2
        equipmentGrantsSkill = outcome != "SKILL_UNAVAILABLE"
        val desired = CharacterPatternSetting(setting("2").rows + setting("1").rows, "back", "always")
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, desired, mapOf("0" to sourceSaved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))

        val result = transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)))

        assertEquals(CharacterTransferStepStatus.FAILED, result.results.last().status)
        assertTrue(submittedFields.none { "ChangePattern" in it || "ChangePosition" in it }, "호환되지 않는 패턴은 POST하지 않는다.")
        assertEquals(outcome != "EQUIPMENT_REJECTED", targetEquipment)
        assertTrue(targetCurrent.rows.all { it.skill == "0" })
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
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

    @ParameterizedTest
    @ValueSource(strings = ["SKILL", "CONDITION", "POSITION", "GUARD"])
    fun `선택한 현재 패턴이 제외되어도 저장 복사와 재진입 뒤 최초 대상 패턴을 보존한다`(unavailable: String) {
        targetCurrent = setting("0").copy(position = "back", guard = "always")
        val original = targetCurrent
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots)))
        val sourcePage = page(source.hofCharacterId, setting(if (unavailable == "SKILL") "9" else "1"),
            mapOf("0" to sourceSaved), skills = 0..9)
        val incompatiblePage = when (unavailable) {
            "CONDITION" -> sourcePage.replace("<option value=\"0\" selected>Always", "<option value=\"9\" selected>Other")
            "POSITION" -> sourcePage.replace("value=\"front\"", "value=\"side\"")
            "GUARD" -> sourcePage.replace("value=\"never\"", "value=\"other\"")
            else -> sourcePage
        }
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId, incompatiblePage))
        val selection = CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0"))))
        assertTrue(transfers.preview(accountId, selection).issues.any {
            it.itemKey == "current-pattern" && it.severity == CharacterTransferIssueSeverity.NEEDS_SELECTION
        })
        var snapshot: CharacterTransferSnapshot? = null

        val first = transfers.execute(accountId, selection, onSnapshot = { snapshot = it })

        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())
        assertEquals(sourceSaved, targetSlots["0"])
        assertEquals(original, targetCurrent, "제외된 현재 패턴 대신 슬롯 복사용 임시 패턴을 남기지 않는다.")

        targetCurrent = sourceSaved
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved))))
        val resumed = transfers.execute(accountId, selection, first.results.map { it.stepId }.toSet(), snapshot)

        assertTrue(resumed.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, resumed.toString())
        assertEquals(original, targetCurrent, "재진입 때 새 원본이나 임시 현재 패턴으로 최초 의도를 바꾸지 않는다.")
        assertEquals(sourceSaved, targetSlots["0"])
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `최초 snapshot 없는 완료 기록에서 현재 패턴이 제외되면 임시 패턴을 원본으로 채택하지 않는다`() {
        targetCurrent = sourceSaved
        targetSlots["0"] = sourceSaved
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, setting("9"), mapOf("0" to sourceSaved), skills = 0..9)))
        val selection = CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0"))))
        var captured: CharacterTransferSnapshot? = null

        assertFailsWith<IllegalStateException> {
            transfers.execute(accountId, selection, setOf("saved-pattern:0:0"),
                onSnapshot = { captured = it })
        }

        assertEquals(null, captured, "원래 설정을 모르는 기록에 임시 설정을 새 원본으로 영속화하지 않는다.")
        assertTrue(submittedCharacters.isEmpty())
        assertEquals(sourceSaved, targetCurrent)
        assertEquals(sourceSaved, targetSlots["0"])
    }

    @Test
    fun `공개 가져오기 작업도 최초 snapshot 없는 보존 재개를 거절하고 다시 시작해도 원본을 만들지 않는다`() {
        targetCurrent = sourceSaved
        targetSlots["0"] = sourceSaved
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, setting("9"), mapOf("0" to sourceSaved), skills = 0..9)))
        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0"))),
            completedStepIds = setOf("saved-pattern:0:0")))

        tasks.removeFirst().run()

        assertEquals(CharacterOperationStatus.FAILED, jobs.find(accountId, started.id).status)
        assertTrue(jobs.find(accountId, started.id).message!!.contains("현재 설정 기록"))
        assertTrue(submittedCharacters.isEmpty())

        // 거절한 시도에서 임시 원본을 저장했다면 다음 startup은 그 값을 신뢰하게 된다.
        val interrupted = jobQueries.findByAccountIdAndId(accountId, started.id)!!
        interrupted.status = CharacterOperationStatus.RUNNING
        interrupted.finishedAt = null
        jobCommands.save(interrupted)
        jobs.resumeIncompleteJobs()
        tasks.removeFirst().run()

        assertEquals(CharacterOperationStatus.FAILED, jobs.find(accountId, started.id).status)
        assertEquals(sourceSaved, targetCurrent)
        assertEquals(sourceSaved, targetSlots["0"])
        assertTrue(submittedCharacters.isEmpty())
    }

    @Test
    fun `현재 패턴을 선택해도 최초 snapshot 없는 장비 보존 재개는 임시 장비를 원본으로 채택하지 않는다`() {
        targetEquipment = true
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true,
                equipmentName = "Unavailable Ring")))
        archive.saveEquipmentPreset(source, 2, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = false)), Instant.now())
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = true)))
        var captured: CharacterTransferSnapshot? = null

        assertFailsWith<IllegalStateException> {
            transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
                CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)),
                setOf("equipment-preset:2:save"), onSnapshot = { captured = it })
        }

        assertEquals(null, captured)
        assertEquals(true, targetEquipment)
        assertEquals("Focus Ring", targetEquipmentName)
        assertTrue(submittedCharacters.isEmpty())
    }

    @Test
    fun `장비 후보 부재로 차단된 재개도 최초 snapshot에 임시 장비를 저장하지 않는다`() {
        targetEquipment = true
        targetEquipmentName = "Retired Ring"
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true,
                equipmentName = "Unavailable Ring")))
        archive.saveEquipmentPreset(source, 2, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = false)), Instant.now())
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = true, equipmentName = targetEquipmentName)))
        var captured: CharacterTransferSnapshot? = null

        assertFailsWith<IllegalArgumentException> {
            transfers.execute(accountId, CharacterTransferSelection(source.id, target.id,
                CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)),
                setOf("equipment-preset:2:save"), onSnapshot = { captured = it })
        }

        assertEquals(null, captured, "실행 차단을 확인하기 전에 임시 장비를 원본으로 영속화하지 않는다.")
        assertEquals(true, targetEquipment)
        assertEquals("Retired Ring", targetEquipmentName)
        assertTrue(submittedCharacters.isEmpty())
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
