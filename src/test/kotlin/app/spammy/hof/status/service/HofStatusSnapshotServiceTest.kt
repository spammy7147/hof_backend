package app.spammy.hof.status.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.external.parser.HofMainStatusParser
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    HofStatusSnapshotQueryRepository::class,
    HofMainStatusParser::class,
    HofStatusSnapshotService::class,
)
class HofStatusSnapshotServiceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var service: HofStatusSnapshotService

    @Test
    fun `complete status creates an account snapshot`() {
        val account = account("status-snapshot-complete")

        assertTrue(service.observe(account.id, completeHtml(time = "5900/6000"), NOW))

        val actual = assertNotNull(service.findLatest(account.id))
        assertEquals("《얼어붙은 손길》공민이", actual.playerName)
        assertEquals(331_708_318L, actual.funds)
        assertEquals(5900, actual.timeCurrent)
        assertEquals(6000, actual.timeMax)
        assertEquals("Nothing", actual.work)
        assertEquals("Nothing", actual.auction)
        assertEquals(NOW, actual.observedAt)
    }

    @Test
    fun `partial or statusless response preserves the latest snapshot`() {
        val account = account("status-snapshot-partial")
        service.observe(account.id, completeHtml(time = "5900/6000"), NOW)

        assertFalse(service.observe(account.id, "<html>Time : 5800/6000</html>", NOW.plusSeconds(1)))
        assertFalse(service.observe(account.id, "<html>captcha</html>", NOW.plusSeconds(2)))

        assertEquals(5900, service.findLatest(account.id)?.timeCurrent)
        assertEquals(NOW, service.findLatest(account.id)?.observedAt)
    }

    @Test
    fun `older request observation cannot replace a newer one`() {
        val account = account("status-snapshot-order")
        service.observe(account.id, completeHtml(time = "5800/6000"), NOW.plusSeconds(2))

        assertFalse(service.observe(account.id, completeHtml(time = "5900/6000"), NOW))

        assertEquals(5800, service.findLatest(account.id)?.timeCurrent)
        assertEquals(NOW.plusSeconds(2), service.findLatest(account.id)?.observedAt)
    }

    @Test
    fun `snapshots are isolated by account`() {
        val first = account("status-snapshot-first")
        val second = account("status-snapshot-second")

        service.observe(first.id, completeHtml(time = "5900/6000"), NOW)
        service.observe(second.id, completeHtml(time = "4200/6000"), NOW)

        assertEquals(5900, service.findLatest(first.id)?.timeCurrent)
        assertEquals(4200, service.findLatest(second.id)?.timeCurrent)
    }

    private fun account(loginId: String): HofAccountEntity = accounts.save(
        HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = NOW),
    )

    private fun completeHtml(time: String): String = """
        <table id="menu2">
          <tr>
            <td>《얼어붙은 손길》공민이</td>
            <td>Funds : ${'$'} 331,708,318<br>Work : Nothing</td>
            <td>Time : $time<br>Auction : Nothing</td>
          </tr>
        </table>
    """.trimIndent()

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-24T10:00:00Z")
    }
}
