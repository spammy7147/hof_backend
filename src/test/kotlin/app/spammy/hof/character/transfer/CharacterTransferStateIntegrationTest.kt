package app.spammy.hof.character.transfer

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.character.entity.CharacterEntity
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
    @MockitoBean private lateinit var hof: HofGateway

    private lateinit var source: CharacterEntity
    private lateinit var target: CharacterEntity
    private var accountId = 0L
    private lateinit var targetRevision: Instant
    private val sourceCurrent = setting("1")
    private val sourceSaved = setting("2")
    private var targetCurrent = setting("0")
    private val targetSlots = mutableMapOf<String, CharacterPatternSetting?>("0" to null, "1" to null)
    private val submittedCharacters = mutableListOf<String>()

    @BeforeEach
    fun prepare() {
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
                HofHttpResponse(200, request.url, page(target.hofCharacterId, targetCurrent, targetSlots), emptyMap())
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

    private fun page(characterId: String, current: CharacterPatternSetting, slots: Map<String, CharacterPatternSetting?>): String {
        val row = current.rows.single()
        return """
            <html><body><div id="menu2">Funds : ${'$'} 100 Time : 6000/6000</div>
            <div class="carpet_frame">검증 Lv.60 Knight</div>
            <form action="index.php?char=$characterId" method="post">
              <select name="judge0"><option value="0" selected>Always</option></select>
              <input type="text" name="quantity0" value="${row.quantity}">
              <select name="skill0">${(0..2).joinToString("") { skill ->
                "<option value='$skill' ${if (row.skill == skill.toString()) "selected" else ""}>Skill $skill</option>"
              }}</select><input type="submit" name="ChangePattern" value="Save">
            </form>
            <form action="index.php?char=$characterId" method="post">
              <input type="radio" name="position" value="front" ${if (current.position == "front") "checked" else ""}>
              <input type="radio" name="position" value="back" ${if (current.position == "back") "checked" else ""}>
              <select name="guard">
                <option value="never" ${if (current.guard == "never") "selected" else ""}>Never</option>
                <option value="always" ${if (current.guard == "always") "selected" else ""}>Always</option>
              </select>
              <input type="submit" name="ChangePosition" value="Save">
            </form>
            ${slots.entries.joinToString("\n") { (slot, saved) -> """
              <form action="index.php?char=$characterId" method="post">
                <input type="button" value="복사"><input type="hidden" name="patternno" value="$slot">
                ${if (saved == null) """<input type="text" name="patternname" maxlength="6"><input type="submit" name="savepattern" value="SAVE">"""
                  else """<input type="submit" name="loadpattern" value="LOAD"><input type="submit" name="delpattern" value="DEL">"""}
              </form>
            """ }}
            </body></html>
        """.trimIndent()
    }

    private fun setting(skill: String) = CharacterPatternSetting(listOf(CharacterPatternRowValue("0", "0", skill)), "front", "never")
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, "https://example.test")
}
