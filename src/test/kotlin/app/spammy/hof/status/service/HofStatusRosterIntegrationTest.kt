package app.spammy.hof.status.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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

/** 실제 저장된 캐릭터 수명주기와 HOF 홈 응답으로 앱 bootstrap 판정을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class HofStatusRosterIntegrationTest {
    @Autowired private lateinit var status: HofStatusService
    @Autowired private lateinit var characters: CharacterQueryRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @MockitoBean private lateinit var hof: HofGateway
    private lateinit var account: HofAccountEntity
    private val now = Instant.parse("2026-09-09T00:00:00Z")
    private var home = HOME

    @BeforeEach
    fun prepare() {
        TransactionTemplate(transactions).executeWithoutResult {
            account = HofAccountEntity(loginId = "status-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = now)
            entityManager.persist(account)
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = now))
        }
        Mockito.`when`(hof.execute(Mockito.eq(account.id), anyRequest(), Mockito.anyMap<String, String>()))
            .thenAnswer { invocation -> HofHttpResponse(200, invocation.getArgument<HofRequest>(1).url, home, emptyMap()) }
    }

    @Test
    fun `활성 전원의 상세가 있으면 사라짐과 보관 기록이 자동 동기화를 요구하지 않는다`() {
        save("111", CharacterLifecycle.ACTIVE, now)
        save("222", CharacterLifecycle.MISSING, null)
        save("333", CharacterLifecycle.ARCHIVED, null)
        home += """<a href="?char=111">활성</a>"""

        val response = status.fetch(account.id)

        assertEquals(1, response.totalCharacterCount)
        assertEquals(1, response.synchronizedCharacterCount)
        assertFalse(response.characterSyncRequired)
        assertEquals(3, characters.findAllByAccountId(account.id).size)
    }

    @Test
    fun `캐릭터 목록을 확인하지 못한 홈을 빈 명단 동기화 완료로 반환하지 않는다`() {
        val error = assertFailsWith<ApiException> { status.fetch(account.id) }
        assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
        assertEquals(emptyList(), characters.findAllByAccountId(account.id))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `현재 명단의 신규 캐릭터와 상세 미동기화 캐릭터는 자동 동기화를 요구한다`(alreadyStored: Boolean) {
        save("111", CharacterLifecycle.ACTIVE, now)
        if (alreadyStored) save("222", CharacterLifecycle.ACTIVE, null)
        home += """<a href="?char=111">첫째</a><a href="?char=222">둘째</a>"""

        val response = status.fetch(account.id)

        assertEquals(2, response.totalCharacterCount)
        assertEquals(1, response.synchronizedCharacterCount)
        assertTrue(response.characterSyncRequired)
    }

    private fun save(hofId: String, lifecycle: CharacterLifecycle, syncedAt: Instant?) {
        TransactionTemplate(transactions).executeWithoutResult {
            entityManager.persist(CharacterEntity(account = account, hofCharacterId = hofId, name = hofId,
                job = "Knight", updatedAt = now, detailSyncedAt = syncedAt, lifecycle = lifecycle))
        }
    }

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, "https://hof.zerosic.com/index.php")

    private companion object {
        val HOME = """
            <table id="menu2"><tr>
            <td>테스트 계정</td><td>Funds : ${'$'} 100<br>Work : Nothing</td>
            <td>Time : 6000/6000<br>Auction : item/funds</td>
            </tr></table>
        """.trimIndent()
    }
}
