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
import kotlin.test.assertFailsWith
import app.spammy.hof.character.pattern.CharacterPatternMutationReceipt
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
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @MockitoBean private lateinit var hof: HofGateway

    private var accountId = 0L
    private var characterId = 0L
    private lateinit var mapCode: String
    private var currentPattern = "unconfigured"
    private val battlePatterns = mutableListOf<String>()
    private val loadedSlots = mutableListOf<String>()
    private var loseNextLoadResponse = false
    private var rotateLoadCookie = false
    private var expectedCookie = "same-session"
    private val sentCookies = mutableListOf<String>()
    private var loseBattleResponse = false

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
            entityManager.persist(CharacterEntity(account = account, hofCharacterId = SECOND_CHARACTER,
                name = "두 번째", job = "Knight", updatedAt = NOW))
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
                    request.formFields["patternno"]?.let { slot ->
                        currentPattern = if (slot == "0") "A" else "B"
                        loadedSlots += slot
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

    private fun characterPage(): String = """
        <html><body><div id="menu2">Funds : ${'$'} 100 Time : 6000/6000</div>
        <div class="carpet_frame">검증 Lv.60 Knight</div>
        <form action="index.php?char=$HOF_CHARACTER" method="post">
          <input type="button" value="A"><input type="hidden" name="patternno" value="0">
          <input type="submit" name="loadpattern" value="LOAD">
        </form>
        <form action="index.php?char=$HOF_CHARACTER" method="post">
          <input type="button" value="B"><input type="hidden" name="patternno" value="1">
          <input type="submit" name="loadpattern" value="LOAD">
        </form></body></html>
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
