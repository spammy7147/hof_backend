package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.pattern.CharacterPatternRemoteFactory
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import jakarta.persistence.EntityManager
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertFailsWith
import app.spammy.hof.character.pattern.CharacterPatternMutationReceipt
import app.spammy.hof.character.pattern.CharacterPatternDraft
import app.spammy.hof.character.pattern.CharacterPatternOperationResult
import app.spammy.hof.character.pattern.CharacterPatternRowValue
import app.spammy.hof.character.service.CharacterPatternService
import app.spammy.hof.character.service.CharacterSnapshotSynchronizer
import app.spammy.hof.character.service.CharacterDeepSyncService
import app.spammy.hof.character.service.CharacterDeepSyncPhase
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.character.transfer.CharacterTransferSelection
import app.spammy.hof.character.transfer.CharacterTransferRequest
import app.spammy.hof.character.transfer.CharacterTransferStepStatus
import app.spammy.hof.external.parser.CharacterDetailParser
import kotlin.test.assertIs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 실제 패턴 명령·전투 조립을 유지하고 HOF의 현재 설정만 메모리 adapter로 제어한다. */
@SpringBootTest
@ActiveProfiles("test")
class BattlePatternReuseIntegrationTest {
    @Autowired private lateinit var battles: BattleRunService
    @Autowired private lateinit var patterns: CharacterPatternRemoteFactory
    @Autowired private lateinit var patternCommands: CharacterPatternService
    @Autowired private lateinit var snapshots: CharacterSnapshotSynchronizer
    @Autowired private lateinit var deepSync: CharacterDeepSyncService
    @Autowired private lateinit var transfers: CharacterTransferService
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoBean private lateinit var hof: HofGateway

    private var accountId = 0L
    private var characterId = 0L
    private var secondCharacterId = 0L
    private lateinit var mapCode: String
    private var currentPattern = "unconfigured"
    private val battlePatterns = mutableListOf<String>()
    private val loadedSlots = mutableListOf<String>()
    private var loseNextLoadResponse = false
    private var rotateLoadCookie = false
    private var expectedCookie = "same-session"
    private val sentCookies = mutableListOf<String>()
    private var loseBattleResponse = false
    private var rejectNextLoad: String? = null

    @BeforeEach
    fun prepare() {
        mapCode = "pattern-${UUID.randomUUID()}"
        TransactionTemplate(transactionManager).executeWithoutResult {
            val account = HofAccountEntity(loginId = mapCode, encryptedPassword = "fixture", createdAt = NOW)
            entityManager.persist(account)
            accountId = account.id
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "same-session", updatedAt = NOW))
            val character = CharacterEntity(account = account, hofCharacterId = HOF_CHARACTER, name = "검증", job = "Knight", updatedAt = NOW)
            entityManager.persist(character)
            characterId = character.id
            val second = CharacterEntity(account = account, hofCharacterId = SECOND_CHARACTER,
                name = "두 번째", job = "Knight", updatedAt = NOW)
            entityManager.persist(second)
            secondCharacterId = second.id
            val map = BattleMapEntity(categoryId = "battle_map", mapCode = mapCode, name = mapCode,
                normalizedName = mapCode, createdAt = NOW, updatedAt = NOW)
            entityManager.persist(map)
            entityManager.persist(AccountBattleMapStateEntity(account = account, battleMap = map,
                rawHref = "index.php?common=$mapCode", lastSeenAt = NOW))
        }
        Mockito.`when`(hof.execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap<String, String>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<HofRequest>(1)
                val cookies = invocation.getArgument<Map<String, String>>(2)
                sentCookies += requireNotNull(cookies["PHPSESSID"])
                check(cookies["PHPSESSID"] == expectedCookie) { "이전 응답의 쿠키로 이어지지 않았습니다." }
                var responseCookies = emptyMap<String, String>()
                val characterRequest = request.url.contains("char=$HOF_CHARACTER")
                if (characterRequest && request.method == HofHttpMethod.POST) {
                    if ("ChangePattern" in request.formFields) {
                        currentPattern = if (request.formFields.getValue("skill0") == "9564") "A" else "B"
                    }
                    request.formFields["patternno"]?.let { slot ->
                        loadedSlots += slot
                        rejectNextLoad?.let { rejection ->
                            rejectNextLoad = null
                            return@thenAnswer HofHttpResponse(
                                if (rejection == "HTTP_500") 500 else 200,
                                if (rejection == "OTHER_CHARACTER") "https://hof.zerosic.com/index.php?char=other" else request.url,
                                when (rejection) {
                                    "INCOMPLETE" -> "<div id='menu2'>Funds : $ 100 Time : 6000/6000</div>"
                                    "REJECTED" -> characterPage() + "<div class='error'>패턴 불러오기가 거부되었습니다.</div>"
                                    "MISSING_SLOT" -> characterPage().replace("name=\"loadpattern\"", "name=\"unavailable\"")
                                    else -> characterPage()
                                }, emptyMap())
                        }
                        currentPattern = if (slot == "0") "A" else "B"
                        if (rotateLoadCookie) {
                            expectedCookie = "rotated-${loadedSlots.size}"
                            responseCookies = mapOf("PHPSESSID" to expectedCookie)
                        }
                        if (loseNextLoadResponse) {
                            loseNextLoadResponse = false
                            throw IllegalStateException("applied but response lost")
                        }
                    }
                } else if (!characterRequest && request.method == HofHttpMethod.POST) {
                    battlePatterns += currentPattern
                    if (loseBattleResponse) throw IOException("battle response lost")
                }
                HofHttpResponse(200, request.url, if (characterRequest) characterPage() else battlePage(), responseCookies)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["REJECTED", "INCOMPLETE", "OTHER_CHARACTER", "HTTP_500", "MISSING_SLOT"])
    fun `확인하지 못한 패턴 로드를 기억하거나 전투에 사용하지 않고 다음 실행에서 다시 로드한다`(rejection: String) {
        patterns.withRemote(accountId, characterId) { it.loadSlot("1") }
        assertEquals("B", currentPattern)
        rejectNextLoad = rejection

        val failure = runCatching { battle() }.exceptionOrNull()
        assertTrue(failure is BattleNotSubmittedException,
            "선로드를 확인하지 못하면 실제 전투 전송 전에 종료해야 한다: $failure")
        assertEquals(emptyList(), battlePatterns)
        assertEquals("B", currentPattern)

        battle()

        assertEquals(listOf("A"), battlePatterns)
        assertEquals(listOf("1", "0", "0"), loadedSlots)
    }

    @Test
    fun `캐릭터에서 B를 불러온 뒤 다음 전투는 다시 선택한 A로 실행한다`() {
        battle()
        patterns.withRemote(accountId, characterId) { it.loadSlot("1") }
        assertEquals("B", currentPattern, "실제 원격 adapter에 B가 적용된 기준선")

        battle()

        assertEquals(listOf("A", "A"), battlePatterns)
        assertEquals(listOf("0", "1", "0"), loadedSlots)
    }

    @Test
    fun `캐릭터 변경 응답을 잃어도 다음 전투는 이전 재사용 기록을 믿지 않는다`() {
        battle()
        loseNextLoadResponse = true
        val receipt = patterns.withRemote(accountId, characterId) { it.loadSlot("1") }
        assertEquals(CharacterPatternMutationReceipt.RESPONSE_LOST, receipt)
        assertEquals("B", currentPattern)

        battle()

        assertEquals(listOf("A", "A"), battlePatterns)
        assertEquals(listOf("0", "1", "0"), loadedSlots)
    }

    @Test
    fun `캐릭터 관측만 수행한 뒤에는 같은 패턴을 다시 로드하지 않는다`() {
        battle()
        patterns.withRemote(accountId, characterId) { it.observe() }

        battle()

        assertEquals(listOf("A", "A"), battlePatterns)
        assertEquals(listOf("0"), loadedSlots)
    }

    @Test
    fun `두 전투 사이 직접 편집한 B를 다음 전투의 선택 패턴 A로 되돌린다`() {
        battle()
        val base = patterns.withRemote(accountId, characterId) { it.observe() }
        val edited = patternCommands.applyDraft(accountId, characterId, base.setting, base.revision,
            CharacterPatternDraft(base.revision, listOf(CharacterPatternRowValue("0", "0", "7777")),
                base.setting.position, base.setting.guard))
        assertIs<CharacterPatternOperationResult.Completed>(edited)
        assertEquals("B", currentPattern)

        battle()

        assertEquals(listOf("A", "A"), battlePatterns)
        assertEquals(listOf("0", "0"), loadedSlots)
    }

    @Test
    fun `설정 가져오기로 B가 된 뒤 다음 전투는 A를 다시 로드한다`() {
        battle()
        snapshots.writeParsed(accountId, SECOND_CHARACTER,
            CharacterDetailParser().parsePage(SECOND_CHARACTER, characterPage("B", SECOND_CHARACTER)))
        snapshots.refresh(accountId, characterId)
        val selection = CharacterTransferSelection(secondCharacterId, characterId,
            CharacterTransferRequest(includeCurrentPattern = true))
        val preview = transfers.preview(accountId, selection)
        val result = transfers.execute(accountId, selection.copy(confirmationToken = preview.confirmationToken))
        assertTrue(result.results.isNotEmpty())
        assertTrue(result.results.all { it.status == CharacterTransferStepStatus.COMPLETED }, result.toString())
        assertEquals("B", currentPattern)

        battle()

        assertEquals(listOf("A", "A"), battlePatterns)
        assertEquals(listOf("0", "0"), loadedSlots)
    }

    @Test
    fun `전체 설정 동기화가 원본을 복구해도 다음 전투는 패턴을 새로 확인한다`() {
        battle()
        val jobId = TransactionTemplate(transactionManager).execute {
            val job = CharacterOperationJobEntity(account = entityManager.getReference(HofAccountEntity::class.java, accountId),
                operationType = CharacterOperationType.DEEP_SYNC, targetCharacterId = characterId,
                recoveryStatus = CharacterRecoveryStatus.NOT_STARTED, startedAt = NOW, updatedAt = NOW)
            entityManager.persist(job)
            job.id
        }
        val result = deepSync.synchronize(accountId, characterId, jobId)
        assertEquals(CharacterDeepSyncPhase.COMPLETED, result.progress.last().phase)
        assertEquals("A", currentPattern)
        assertEquals(listOf("0", "0", "1"), loadedSlots, "깊은 동기화에서 A와 B를 실제로 불러온다.")

        battle()

        assertEquals(listOf("A", "A"), battlePatterns)
        assertEquals(listOf("0", "0", "1", "0"), loadedSlots)
    }

    @Test
    fun `패턴 로드에서 갱신한 쿠키로 같은 사이클의 전투를 제출한다`() {
        rotateLoadCookie = true

        battle()

        assertEquals(listOf("A"), battlePatterns)
    }

    @Test
    fun `두 캐릭터의 패턴 로드가 각각 갱신한 쿠키를 다음 요청에 전달한다`() {
        rotateLoadCookie = true

        battles.runBattle(accountId, RunBattleRequest("battle_map", mapCode,
            listOf(HOF_CHARACTER, SECOND_CHARACTER),
            listOf(BattlePatternLoadRequest(HOF_CHARACTER, 0), BattlePatternLoadRequest(SECOND_CHARACTER, 0))),
            HofRequestOrigin.AUTOMATION)

        assertEquals(listOf("same-session", "rotated-1", "rotated-2"), sentCookies)
        assertEquals(listOf("A"), battlePatterns)
    }

    @Test
    fun `전투 제출 후 응답을 잃어도 다음 사이클에 이전 쿠키 범위가 남지 않는다`() {
        rotateLoadCookie = true
        loseBattleResponse = true
        assertFailsWith<IOException> { battle() }
        assertEquals(listOf("A"), battlePatterns, "전투 미전송으로 분류하지 않는다.")

        // 다음 호출의 저장 쿠키를 새 기준으로 사용해야 한다.
        expectedCookie = "same-session"
        rotateLoadCookie = false
        loseBattleResponse = false
        battle()

        assertEquals(listOf("same-session", "rotated-1", "same-session"), sentCookies)
        assertEquals(listOf("A", "A"), battlePatterns)
    }

    private fun battle() = battles.runBattle(accountId, RunBattleRequest("battle_map", mapCode,
        listOf(HOF_CHARACTER), listOf(BattlePatternLoadRequest(HOF_CHARACTER, 0))), HofRequestOrigin.AUTOMATION)

    private fun characterPage(pattern: String = currentPattern, id: String = HOF_CHARACTER): String = """
        <html><body><div id="menu2">Funds : ${'$'} 100 Time : 6000/6000</div>
        <div class="carpet_frame">검증 Lv.60 Knight</div>
        ${app.spammy.hof.character.service.currentPatternForm(if (pattern == "A") "9564" else "7777")}
        <form action="index.php?char=$id" method="post">
          <input type="button" value="A"><input type="hidden" name="patternno" value="0">
          <input type="submit" name="loadpattern" value="LOAD">
        </form>
        <form action="index.php?char=$id" method="post">
          <input type="button" value="B"><input type="hidden" name="patternno" value="1">
          <input type="submit" name="loadpattern" value="LOAD">
        </form>
        <form method="post"><table>
          ${(1..12).joinToString("") { "<tr><td class='align-right'>Empty$it</td><td><input name='spot' value='empty$it'></td></tr>" }}
        </table><input type="submit" name="remove_all" value="Remove"></form>
        <script>function Listtype_equip(mode) {}</script>
        <form method="post"><div id="list0">None.</div><input type="submit" name="equip_item" value="Equip"></form>
        </body></html>
    """.trimIndent()

    private fun battlePage(): String = """
        <html><body><div id="menu2">Funds : ${'$'} 100 Time : 6000/6000</div>
        <h2>Show Detail( 1 turns. )</h2><h1>검증은(는) 승리했다!</h1>
        <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 10</div>
        <div>남은 HP : 90/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : ${'$'} 1</div>
        </body></html>
    """.trimIndent()

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, "https://hof.zerosic.com/index.php")

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-09T00:00:00Z")
        const val HOF_CHARACTER = "pattern-reuse-character"
        const val SECOND_CHARACTER = "pattern-reuse-character-2"
    }
}
