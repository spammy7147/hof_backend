package app.spammy.hof.character.transfer

import app.spammy.hof.character.transfer.CharacterTransferFixture.page
import app.spammy.hof.character.transfer.CharacterTransferFixture.setting
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.RefreshTokenService
import app.spammy.hof.auth.service.AuthService
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.service.UnifiedAutomationService
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
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
import org.springframework.jdbc.core.JdbcTemplate

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
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var automation: UnifiedAutomationService
    @Autowired private lateinit var refreshTokens: RefreshTokenService
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var jdbc: JdbcTemplate
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
    private var targetStatusPoints: Int? = null
    private val targetEquipmentSlots = mutableMapOf<Int, String?>()
    private var changeEquipmentCandidateAfterClear = false
    private val tasks = ArrayDeque<Runnable>()
    private var onFirstPost: (() -> Unit)? = null
    private var onFirstObservation: (() -> Unit)? = null

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
                if (request.method == HofHttpMethod.GET) onFirstObservation?.also { onFirstObservation = null }?.invoke()
                if (request.method == HofHttpMethod.POST) {
                    submittedCharacters += characterId
                    val fields = request.formFields
                    submittedFields += fields
                    onFirstPost?.also { onFirstPost = null }?.invoke()
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
                    equipmentCandidateValue = equipmentCandidateValue, equipmentName = targetEquipmentName) +
                    targetStatusPoints?.let { statusPointForm(it) }.orEmpty()
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
    fun `미리보기 뒤 원본 패턴이 달라지면 최초 가져오기 작업은 변경 요청 전에 멈춘다`() {
        val request = CharacterTransferRequest(includeCurrentPattern = true)
        val preview = transfers.preview(accountId, CharacterTransferSelection(source.id, target.id, request))
        assertTrue(!preview.confirmationToken.isNullOrBlank())
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, setting("2"), mapOf("0" to sourceSaved))))

        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            request, confirmationToken = preview.confirmationToken))
        tasks.removeFirst().run()

        val result = jobs.find(accountId, started.id).transfer!!
        assertEquals("PREVIEW_CHANGED", result.outcome?.name)
        assertTrue(submittedFields.isEmpty(), "확인하지 않은 변경 패턴을 HOF에 보내지 않는다")
        assertEquals(setting("0"), targetCurrent)
        assertTrue(result.results.isEmpty())
        assertTrue(!objectMapper.readTree(jobQueries.findByAccountIdAndId(accountId, started.id)!!.requestPayload).hasNonNull("snapshot"))
        val changed = result.preview!!
        assertTrue(changed.confirmationToken != preview.confirmationToken)
        assertEquals(setting("2"), changed.steps.filterIsInstance<CharacterTransferStep.ApplyCurrentPattern>().single().setting)

        val confirmed = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            request, confirmationToken = changed.confirmationToken))
        tasks.removeFirst().run()

        assertEquals(CharacterTransferOutcome.COMPLETED, jobs.find(accountId, confirmed.id).transfer!!.outcome)
        assertEquals(setting("2"), targetCurrent)
        assertTrue(submittedFields.isNotEmpty())
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["PATTERN", "EQUIPMENT", "STATUS_POINTS", "SLOT"])
    fun `미리보기 뒤 대상 값이 바뀌면 새 미리보기를 보여주고 변경 행동을 보내지 않는다`(changed: String) {
        if (changed == "EQUIPMENT") {
            targetEquipment = true
            snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
                page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true)))
        }
        if (changed == "STATUS_POINTS") {
            targetStatusPoints = 20
            snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
                page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved)) + statusPointForm(0, 20)))
        }
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, targetEquipment) +
                targetStatusPoints?.let { statusPointForm(it) }.orEmpty()))
        val request = CharacterTransferRequest(includeCurrentPattern = true,
            includeStats = changed == "STATUS_POINTS", includeEquipment = changed == "EQUIPMENT",
            savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))
        val preview = transfers.preview(accountId, CharacterTransferSelection(source.id, target.id, request))
        when (changed) {
            "PATTERN" -> targetCurrent = setting("2")
            "EQUIPMENT" -> targetEquipmentName = "Guard Ring"
            "STATUS_POINTS" -> targetStatusPoints = 0
            "SLOT" -> targetSlots["0"] = setting("1")
        }

        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            request, confirmationToken = preview.confirmationToken))
        tasks.removeFirst().run()

        val result = jobs.find(accountId, started.id).transfer!!
        assertEquals(CharacterTransferOutcome.PREVIEW_CHANGED, result.outcome)
        assertTrue(submittedFields.isEmpty())
        assertTrue(result.results.isEmpty())
        assertTrue(result.preview!!.confirmationToken != preview.confirmationToken)
        if (changed == "STATUS_POINTS") {
            assertTrue(preview.steps.any { it is CharacterTransferStep.AllocateStats })
            assertTrue(result.preview.steps.none { it is CharacterTransferStep.AllocateStats })
            assertTrue(result.preview.issues.any { it.code == "STATUS_POINTS_INSUFFICIENT" })
        }
        if (changed == "SLOT") {
            assertEquals(false, preview.steps.filterIsInstance<CharacterTransferStep.SavePatternSlot>().single().replacesExisting)
            assertEquals(true, result.preview.steps.filterIsInstance<CharacterTransferStep.SavePatternSlot>().single().replacesExisting)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["STATS", "SKILLS", "SLOTS"])
    fun `선택한 대상 구역을 다시 관측하지 못하면 저장된 과거 값으로 확인을 통과시키지 않는다`(missing: String) {
        if (missing == "STATS") {
            targetStatusPoints = 20
            snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
                page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved)) + statusPointForm(0, 20)))
        }
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots) +
                targetStatusPoints?.let { statusPointForm(it) }.orEmpty() +
                if (missing == "SKILLS") "<h4>Skill</h4>" else ""))
        val request = CharacterTransferRequest(includeCurrentPattern = true,
            includeStats = missing == "STATS", includeSkills = missing == "SKILLS",
            savedPatternMappings = if (missing == "SLOTS") listOf(CharacterSavedPatternMapping("0", "0")) else emptyList())
        val preview = transfers.preview(accountId, CharacterTransferSelection(source.id, target.id, request))
        targetStatusPoints = null
        if (missing == "SLOTS") targetSlots.clear()

        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            request, confirmationToken = preview.confirmationToken))
        tasks.removeFirst().run()

        assertEquals(CharacterOperationStatus.FAILED, jobs.find(accountId, started.id).status)
        assertTrue(submittedFields.isEmpty(), "관측 실패 시 과거 값으로 미리보기 일치를 판정하지 않는다")
        assertTrue(!objectMapper.readTree(jobQueries.findByAccountIdAndId(accountId, started.id)!!.requestPayload).hasNonNull("snapshot"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "WRONG", "MODIFIED_SELECTION"])
    fun `확인 정보가 없거나 다른 선택의 확인값으로 최초 작업을 시작해도 변경하지 않는다`(invalid: String) {
        val request = CharacterTransferRequest(includeCurrentPattern = true)
        val preview = transfers.preview(accountId, CharacterTransferSelection(source.id, target.id, request))
        val changedRequest = if (invalid == "MODIFIED_SELECTION") request.copy(
            savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0"))) else request
        val token = when (invalid) { "MISSING" -> null; "WRONG" -> "invalid"; else -> preview.confirmationToken }

        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            changedRequest, confirmationToken = token))
        tasks.removeFirst().run()

        val result = jobs.find(accountId, started.id).transfer!!
        assertEquals(CharacterTransferOutcome.PREVIEW_CHANGED, result.outcome)
        assertTrue(submittedFields.isEmpty())
        assertTrue(!result.preview!!.confirmationToken.isNullOrBlank())
        assertTrue(!objectMapper.readTree(jobQueries.findByAccountIdAndId(accountId, started.id)!!.requestPayload).hasNonNull("snapshot"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["RUNNING", "USER_PAUSE", "USER_STOP", "AUTH", "AUTH_RELOGIN"])
    fun `미리보기 변경으로 행동하지 않은 작업의 복귀도 사용자 정지와 인증 종료를 보존한다`(control: String) {
        val account = TransactionTemplate(transactions).execute {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.UNION,
                priority = 0, enabled = true, createdAt = Instant.now(), updatedAt = Instant.now()))
            account
        }!!
        val authToken = refreshTokens.issue(account, "NATIVE")
        automation.startTyped(accountId)
        val request = CharacterTransferRequest(includeCurrentPattern = true)
        val preview = transfers.preview(accountId, CharacterTransferSelection(source.id, target.id, request))
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, setting("2"), mapOf("0" to sourceSaved))))
        onFirstObservation = {
            when (control) {
                "USER_PAUSE" -> automation.pauseTyped(accountId)
                "USER_STOP" -> automation.stopTyped(accountId)
                "AUTH", "AUTH_RELOGIN" -> {
                    auth.logout(authToken.value)
                    if (control == "AUTH_RELOGIN") refreshTokens.issue(account, "NATIVE")
                }
            }
        }

        val started = jobs.startTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            request, confirmationToken = preview.confirmationToken))
        tasks.removeFirst().run()

        assertEquals(CharacterTransferOutcome.PREVIEW_CHANGED, jobs.find(accountId, started.id).transfer!!.outcome)
        assertTrue(submittedFields.isEmpty())
        assertEquals(when (control) {
            "RUNNING" -> TypedAutomationLifecycle.RUNNING
            "USER_STOP" -> TypedAutomationLifecycle.STOPPED
            else -> TypedAutomationLifecycle.PAUSED
        }, automation.getTyped(accountId).runtime.lifecycle)
        assertEquals(true, jobQueries.findByAccountIdAndId(accountId, started.id)!!.automationReleased)
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

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(true, targetEquipment)
        val expectedRows = if (sourceRows == 1) listOf("2", "0") else listOf("2", "1")
        assertEquals(expectedRows, targetCurrent.rows.map { it.skill })
        assertEquals("back", targetCurrent.position)
        assertEquals("always", targetCurrent.guard)
        assertEquals(CharacterTransferOutcome.COMPLETED, result.outcome)
        assertEquals(true, result.finalSettingsConfirmed)
        assertEquals(targetCurrent, result.currentSettings?.pattern)
        assertEquals(listOf("Focus Ring"), result.currentSettings?.equipment?.map { it.name })
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
        val first = executeConfirmed(accountId, selection, onSnapshot = { snapshot = it })
        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())

        // 완료 저장 뒤 HOF에서 현재 장비가 바뀌어도 과거 완료 ID를 최종 상태로 믿지 않는다.
        targetEquipment = false
        targetCurrent = setting("0")
        if (candidateChange == "BEFORE_RESUME") equipmentCandidateValue = "ring-new"
        changeEquipmentCandidateAfterClear = candidateChange == "AFTER_CLEAR"
        val resumed = executeConfirmed(accountId, selection, first.results.map { it.stepId }.toSet(), snapshot)

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
        val first = executeConfirmed(accountId, selection, onSnapshot = { snapshot = it })
        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())
        val original = checkNotNull(snapshot)
        val legacy = original.copy(source = original.source.copy(equipment = original.source.equipment.map { it.copy(identity = null) }))
        submittedFields.clear()

        assertFailsWith<IllegalArgumentException> {
            executeConfirmed(accountId, selection, first.results.map { it.stepId }.toSet(), legacy)
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

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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

        val failed = executeConfirmed(accountId, selection)

        assertEquals(CharacterTransferStepStatus.FAILED, failed.results.single { it.stepId == "equipment-preset:2:save" }.status)
        assertEquals(if (outcome == "SUBMISSIONS_IGNORED") "Guard Ring" else null, targetEquipmentSlots[2])
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)

        ignoredEquipmentSave = null
        ignoreEquipmentLoad = false
        ignoreEquipment = false
        rejectEquipment = false
        val completed = executeConfirmed(accountId, selection)

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

        val failed = executeConfirmed(accountId, selection, onSnapshot = { snapshot = it })

        assertEquals(CharacterTransferStepStatus.FAILED, failed.results.single { it.stepId == "equipment-preset:2:save" }.status)
        assertEquals(true, targetEquipment)
        assertEquals("Guard Ring", targetEquipmentName)
        assertEquals(originalPattern, targetCurrent)

        assertEquals(CharacterTransferOutcome.PARTIALLY_APPLIED, failed.outcome)
        assertEquals(true, failed.finalSettingsConfirmed, "저장 실패와 현재 설정 보존 성공은 구분한다.")
        assertEquals(listOf("Guard Ring"), failed.currentSettings?.equipment?.map { it.name })

        targetEquipmentName = "Focus Ring"
        ignoreEquipmentLoad = false
        val resumed = executeConfirmed(accountId, selection,
            failed.results.filter { it.status == CharacterTransferStepStatus.COMPLETED }.map { it.stepId }.toSet(), snapshot)

        assertTrue(resumed.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, resumed.toString())
        assertTrue(2 in targetEquipmentSlots && targetEquipmentSlots[2] == null)
        assertEquals(true, targetEquipment)
        assertEquals("Guard Ring", targetEquipmentName)
        assertEquals(originalPattern, targetCurrent)
        assertEquals(CharacterTransferOutcome.COMPLETED, resumed.outcome)
        assertEquals(true, resumed.finalSettingsConfirmed)
        assertEquals(listOf("Guard Ring"), resumed.currentSettings?.equipment?.map { it.name })
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })

        submittedFields.clear()
        assertFailsWith<IllegalArgumentException> {
            executeConfirmed(accountId, selection, snapshot = checkNotNull(snapshot).copy(originalEquipment = null))
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
        val first = executeConfirmed(accountId, selection, onSnapshot = { snapshot = it })
        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())
        assertEquals(mapOf<Int, String?>(1 to "Focus Ring", 2 to "Guard Ring"), targetEquipmentSlots)
        assertEquals(false, targetEquipment)
        assertEquals(setting("0"), targetCurrent)

        targetEquipment = true
        targetEquipmentName = "Guard Ring"
        targetEquipmentSlots[1] = "Guard Ring"
        targetEquipmentSlots[2] = "Focus Ring"
        val resumed = executeConfirmed(accountId, selection, first.results.map { it.stepId }.toSet(), snapshot)

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

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)))

        assertEquals(CharacterTransferStepStatus.FAILED, result.results.last().status)
        assertTrue(submittedFields.none { "ChangePattern" in it || "ChangePosition" in it }, "호환되지 않는 패턴은 POST하지 않는다.")
        assertEquals(outcome != "EQUIPMENT_REJECTED", targetEquipment)
        assertTrue(targetCurrent.rows.all { it.skill == "0" })
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `원격 설정이 같으면 저장 직전 재조회로 revision이 달라져도 가져오기를 허용한다`() {
        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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
        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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

        val first = executeConfirmed(accountId, selection, onSnapshot = { snapshot = it })

        assertTrue(first.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, first.toString())
        assertEquals(sourceSaved, targetSlots["0"])
        assertEquals(original, targetCurrent, "제외된 현재 패턴 대신 슬롯 복사용 임시 패턴을 남기지 않는다.")

        targetCurrent = sourceSaved
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved))))
        val resumed = executeConfirmed(accountId, selection, first.results.map { it.stepId }.toSet(), snapshot)

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
            executeConfirmed(accountId, selection, setOf("saved-pattern:0:0"),
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
        val started = startConfirmedTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
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
            executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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
            executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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
        val started = startConfirmedTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
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
        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
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
            executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
                CharacterTransferRequest(savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
        }

        assertEquals(originalCurrent, targetCurrent)
        assertTrue(submittedCharacters.isEmpty())
    }

    @Test
    fun `완료된 최종 패턴 단계도 재개 시 실제 현재 설정을 다시 확인한다`() {
        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true)), setOf("current-pattern"))

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(sourceCurrent, targetCurrent, "완료 checkpoint만으로 현재 설정을 확인했다고 간주하지 않는다.")
    }

    @Test
    fun `저장 패턴의 저장이 거부되어도 임시 패턴을 대상의 현재 설정으로 남기지 않는다`() {
        val originalCurrent = targetCurrent
        rejectedSaveSlot = "0"

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))

        assertEquals(CharacterTransferStepStatus.FAILED, result.results.first().status)
        assertEquals(CharacterTransferStepStatus.COMPLETED, result.results.last().status)
        assertEquals(originalCurrent, targetCurrent)
        assertEquals(null, targetSlots["0"])
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `가져오기 작업의 마지막 적용이 거부되면 부분 반영 결과와 실제 현재 설정을 함께 반환한다`() {
        rejectedCurrentSkill = "1"
        val started = startConfirmedTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))

        tasks.removeFirst().run()

        val response = objectMapper.valueToTree<JsonNode>(jobs.find(accountId, started.id)).path("transfer")
        assertEquals("PARTIALLY_APPLIED", response.path("outcome").asText())
        assertEquals("2", response.path("currentSettings").path("pattern").path("rows").path(0).path("skill").asText())
        assertEquals("front", response.path("currentSettings").path("pattern").path("position").asText())
        assertEquals("never", response.path("currentSettings").path("pattern").path("guard").asText())
        assertEquals(false, response.path("finalSettingsConfirmed").asBoolean())
        assertEquals(sourceSaved, targetCurrent)
        assertEquals(sourceSaved, targetSlots["0"])
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["OTHER_CHARACTER", "MISSING_QUANTITY", "ERROR"])
    fun `단계가 모두 완료돼도 마지막 관측이 불완전하면 재확인 필요로 반환한다`(variant: String) {
        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true)), onStepResult = {
            if (it.stepId == "current-pattern") observationVariant = variant
        })

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(sourceCurrent, targetCurrent)
        assertEquals(CharacterTransferOutcome.RECHECK_REQUIRED, result.outcome)
        assertEquals(null, result.currentSettings, "이전 단계의 관측을 마지막 현재 설정으로 재사용하지 않는다.")
        assertEquals(false, result.finalSettingsConfirmed)
        assertTrue(result.message.orEmpty().contains("확인하지 못했습니다"))
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @ParameterizedTest
    @ValueSource(strings = ["PATTERN", "EQUIPMENT"])
    fun `모든 단계 완료 뒤 최종 현재 설정이 계획과 달라지면 실제 관측과 불일치를 반환한다`(changed: String) {
        targetEquipment = false
        snapshots.writeParsed(accountId, source.hofCharacterId, parser.parsePage(source.hofCharacterId,
            page(source.hofCharacterId, sourceCurrent, mapOf("0" to sourceSaved), equipment = true)))
        snapshots.writeParsed(accountId, target.hofCharacterId, parser.parsePage(target.hofCharacterId,
            page(target.hofCharacterId, targetCurrent, targetSlots, equipment = false)))

        val result = executeConfirmed(accountId, CharacterTransferSelection(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true, includeEquipment = true)), onStepResult = {
            if (it.stepId == "current-pattern") {
                if (changed == "PATTERN") targetCurrent = targetCurrent.copy(position = "back")
                else targetEquipmentName = "Guard Ring"
            }
        })

        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals(CharacterTransferOutcome.PARTIALLY_APPLIED, result.outcome)
        assertEquals(false, result.finalSettingsConfirmed)
        assertEquals(targetCurrent, result.currentSettings?.pattern)
        assertEquals(listOf(if (changed == "EQUIPMENT") "Guard Ring" else "Focus Ring"),
            result.currentSettings?.equipment?.map { it.name })
        assertTrue(result.message.orEmpty().contains("계획과 다릅니다"))
        assertTrue(submittedCharacters.all { it == target.hofCharacterId })
    }

    @Test
    fun `공개 작업은 현재와 저장 패턴을 함께 확인한 완료 결과를 보존하고 구형 결과도 읽는다`() {
        val started = startConfirmedTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
        tasks.removeFirst().run()

        val completed = jobs.find(accountId, started.id)
        assertEquals(CharacterOperationStatus.COMPLETED, completed.status)
        assertEquals(CharacterTransferOutcome.COMPLETED, completed.transfer?.outcome)
        assertEquals(true, completed.transfer?.finalSettingsConfirmed)
        assertEquals(sourceCurrent, completed.transfer?.currentSettings?.pattern)
        assertEquals(sourceSaved, targetSlots["0"])
        assertEquals(null, completed.transfer?.currentSettings?.equipment)

        val old = jobQueries.findByAccountIdAndId(accountId, started.id)!!
        old.resultPayload = """{"targetCharacterId":${target.id},"results":[{"stepId":"current-pattern","status":"COMPLETED","message":""}],"nextStepIndex":1}"""
        jobCommands.save(old)
        val legacy = jobs.find(accountId, started.id).transfer!!
        assertEquals(CharacterTransferStepStatus.COMPLETED, legacy.results.single().status)
        assertEquals(null, legacy.outcome)
        assertEquals(null, legacy.currentSettings)
        assertEquals(false, legacy.finalSettingsConfirmed)
    }

    @AfterEach
    fun removeAccount() {
        jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["JOB", "CURRENT", "STARTUP"])
    fun `최종 결과 저장 뒤 복귀 DB 쓰기가 실패해도 결과를 보존하고 보호를 해제한다`(retry: String) {
        val account = TransactionTemplate(transactions).execute {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.UNION,
                priority = 0, enabled = true, createdAt = Instant.now(), updatedAt = Instant.now()))
            account
        }!!
        refreshTokens.issue(account, "NATIVE")
        automation.startTyped(accountId)
        val started = startConfirmedTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true)))
        val columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'character_operation_jobs' order by ordinal_position", String::class.java)
        val released = columns.indexOf("automation_released")
        val owner = columns.indexOf("account_id")
        check(released >= 0 && owner >= 0)
        // 실제 DB adapter가 복귀 기록의 첫 쓰기만 거부한다. 작업·runtime 구현은 그대로 실행한다.
        jdbc.execute("""create trigger transfer_release_fault before update on character_operation_jobs for each row as ${'$'}${'$'}
            org.h2.api.Trigger create() {
                final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
                return (connection, oldRow, newRow) -> {
                    if (((Number)newRow[$owner]).longValue() == $accountId && Boolean.TRUE.equals(newRow[$released]) && pending.getAndSet(false)) {
                        throw new java.sql.SQLException("fixture release unavailable");
                    }
                };
            }
            ${'$'}${'$'}""")
        try {
            tasks.removeFirst().run()
            val posts = submittedFields.toList()
            assertEquals(TypedAutomationLifecycle.PAUSED, automation.getTyped(accountId).runtime.lifecycle)
            when (retry) {
                "JOB" -> jobs.find(accountId, started.id)
                "CURRENT" -> assertEquals(null, jobs.findCurrent(accountId))
                "STARTUP" -> jobs.resumeIncompleteJobs()
            }
            assertEquals(TypedAutomationLifecycle.RUNNING, automation.getTyped(accountId).runtime.lifecycle)
            val result = jobs.find(accountId, started.id)
            assertEquals(CharacterOperationStatus.COMPLETED, result.status, result.toString())
            assertEquals(CharacterTransferOutcome.COMPLETED, result.transfer?.outcome)
            assertEquals(sourceCurrent, targetCurrent)
            assertEquals(TypedAutomationLifecycle.RUNNING, automation.getTyped(accountId).runtime.lifecycle)
            assertEquals(posts, submittedFields, "보호 해제 재시도는 원격 설정을 다시 제출하지 않는다.")
        } finally {
            jdbc.execute("drop trigger transfer_release_fault")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["RUNNING", "USER_PAUSE", "USER_STOP", "AUTH", "AUTH_RELOGIN", "INITIAL_PAUSED",
        "INITIAL_STOPPED", "FINAL_REJECTED", "SAVE_REJECTED", "FINAL_UNOBSERVED"])
    fun `가져오기 완료 뒤 최종 설정과 사용자 실행 의도가 허용할 때만 자동화로 복귀한다`(control: String) {
        val account = TransactionTemplate(transactions).execute {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.UNION,
                priority = 0, enabled = true, createdAt = Instant.now(), updatedAt = Instant.now()))
            account
        }!!
        val token = refreshTokens.issue(account, "NATIVE")
        assertEquals(TypedAutomationLifecycle.RUNNING, automation.startTyped(accountId).runtime.lifecycle)
        when (control) {
            "INITIAL_PAUSED" -> automation.pauseTyped(accountId)
            "INITIAL_STOPPED" -> automation.stopTyped(accountId)
            "FINAL_REJECTED" -> rejectedCurrentSkill = "1"
            "SAVE_REJECTED" -> rejectedSaveSlot = "0"
        }
        onFirstPost = {
            when (control) {
                "USER_PAUSE" -> automation.pauseTyped(accountId)
                "USER_STOP" -> automation.stopTyped(accountId)
                "AUTH", "AUTH_RELOGIN" -> {
                    auth.logout(token.value)
                    if (control == "AUTH_RELOGIN") refreshTokens.issue(account, "NATIVE")
                }
                "FINAL_UNOBSERVED" -> observationVariant = "ERROR"
            }
        }

        val started = startConfirmedTransfer(accountId, CharacterTransferExecuteRequest(source.id, target.id,
            CharacterTransferRequest(includeCurrentPattern = true,
                savedPatternMappings = listOf(CharacterSavedPatternMapping("0", "0")))))
        tasks.removeFirst().run()

        val completed = jobs.find(accountId, started.id)
        val outcome = when (control) {
            "FINAL_REJECTED", "SAVE_REJECTED" -> CharacterTransferOutcome.PARTIALLY_APPLIED
            "FINAL_UNOBSERVED" -> CharacterTransferOutcome.RECHECK_REQUIRED
            else -> CharacterTransferOutcome.COMPLETED
        }
        assertEquals(outcome, completed.transfer?.outcome, completed.toString())
        if (control !in setOf("FINAL_REJECTED", "FINAL_UNOBSERVED")) assertEquals(sourceCurrent, targetCurrent)
        if (control !in setOf("SAVE_REJECTED", "FINAL_UNOBSERVED")) assertEquals(sourceSaved, targetSlots["0"])
        val lifecycle = when (control) {
            "RUNNING", "SAVE_REJECTED" -> TypedAutomationLifecycle.RUNNING
            "USER_STOP", "INITIAL_STOPPED" -> TypedAutomationLifecycle.STOPPED
            else -> TypedAutomationLifecycle.PAUSED
        }
        assertEquals(lifecycle, automation.getTyped(accountId).runtime.lifecycle)
        assertEquals(null, jobs.findCurrent(accountId), "가져오기를 깊은 동기화 복구 작업으로 표시하지 않는다.")
        if (control in setOf("FINAL_REJECTED", "FINAL_UNOBSERVED")) {
            assertEquals(TypedAutomationLifecycle.RUNNING, automation.resumeTyped(accountId).runtime.lifecycle,
                "사용자가 현재 설정을 확인한 뒤 명시적으로 재개할 수 있어야 한다.")
        }
    }

    private fun executeConfirmed(
        accountId: Long,
        selection: CharacterTransferSelection,
        completedStepIds: Set<String> = emptySet(),
        snapshot: CharacterTransferSnapshot? = null,
        onSnapshot: (CharacterTransferSnapshot) -> Unit = {},
        onStepResult: (CharacterTransferStepResult) -> Unit = {},
    ): CharacterTransferExecutionResult {
        val confirmed = if (snapshot != null) selection else selection.copy(
            confirmationToken = transfers.preview(accountId, selection).confirmationToken)
        return transfers.execute(accountId, confirmed, completedStepIds, snapshot, onSnapshot, onStepResult)
    }

    private fun startConfirmedTransfer(accountId: Long, request: CharacterTransferExecuteRequest) =
        jobs.startTransfer(accountId, request.copy(confirmationToken = transfers.preview(accountId,
            CharacterTransferSelection(request.sourceCharacterId, request.targetCharacterId, request.transfer)).confirmationToken))

    private fun statusPointForm(points: Int, strength: Int = 10) =
        "<table>" + mapOf("Exp" to "0/100", "HP" to "100", "SP" to "10", "STR" to "$strength",
            "INT" to "10", "DEX" to "10", "SPD" to "10", "LUK" to "10").entries.joinToString("") { (name, value) ->
            "<tr><td><font>$name</font></td><td>$value</td></tr>"
        } + "</table><form>Point : $points<select name='upStr'><option value='0'>0</option></select></form>"

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, "https://example.test")
}
