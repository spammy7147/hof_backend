package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.HofMainStatusParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import app.spammy.hof.status.service.HofStatusSnapshotService
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
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
    CharacterQueryRepository::class,
    CharacterIdentityQueryRepository::class,
    HofStatusSnapshotQueryRepository::class,
    HofMainStatusParser::class,
    LoginStateParser::class,
    CharacterRosterParser::class,
    CharacterService::class,
    HofStatusSnapshotService::class,
    CharacterRosterObservationService::class,
    CharacterRosterObservationTransaction::class,
    AccountHofMutationFence::class,
    CharacterRosterObservationServiceTest.ClockConfig::class,
)
class CharacterRosterObservationServiceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var characters: CharacterQueryRepository
    @Autowired private lateinit var statusSnapshots: HofStatusSnapshotService
    @Autowired private lateinit var observer: CharacterRosterObservationService
    @Autowired private lateinit var mutationFence: AccountHofMutationFence

    @Test
    fun `logged-in home response reconciles roster metadata and lifecycle`() {
        val account = account("roster-home")
        createStatusSnapshot(account)

        assertTrue(observer.observe(account.id, homeResponse(rosterHtml(
            card("101", "알파", 10, "Knight"),
            card("202", "베타", 20, "Mage"),
        )), NOW))

        val initial = characters.findAllByAccountId(account.id).associateBy { it.hofCharacterId }
        assertEquals(setOf("101", "202"), initial.keys)
        assertEquals("알파", initial.getValue("101").name)
        assertEquals(10, initial.getValue("101").level)
        assertEquals("Knight", initial.getValue("101").job)
        assertEquals(0, initial.getValue("101").rosterOrder)

        val nextObservation = NOW.plusSeconds(5)
        assertTrue(observer.observe(account.id, homeResponse(rosterHtml(
            card("202", "베타 갱신", 21, "Arch Mage"),
            card("303", "감마", 30, "Priest"),
        )), nextObservation))

        val reconciled = characters.findAllByAccountId(account.id).associateBy { it.hofCharacterId }
        assertEquals(CharacterLifecycle.MISSING, reconciled.getValue("101").lifecycle)
        assertEquals(nextObservation, reconciled.getValue("101").missingSince)
        assertEquals(CharacterLifecycle.ACTIVE, reconciled.getValue("202").lifecycle)
        assertEquals("베타 갱신", reconciled.getValue("202").name)
        assertEquals(21, reconciled.getValue("202").level)
        assertEquals("Arch Mage", reconciled.getValue("202").job)
        assertEquals(0, reconciled.getValue("202").rosterOrder)
        assertEquals(CharacterLifecycle.ACTIVE, reconciled.getValue("303").lifecycle)
        assertEquals(1, reconciled.getValue("303").rosterOrder)
        assertEquals(nextObservation, statusSnapshots.findLatest(account.id)?.characterRosterObservedAt)
    }

    @Test
    fun `logged-out or non-home response is ignored`() {
        val account = account("roster-rejected")
        createStatusSnapshot(account)
        val loggedOut = """
            <form><input name="id"><input name="pass"><input name="Login"></form>
            <a href="?char=999">표시되면 안 됨</a>
        """.trimIndent()

        assertFalse(observer.observe(account.id, homeResponse(loggedOut), NOW))
        assertFalse(observer.observe(
            account.id,
            homeResponse(rosterHtml(card("101", "알파", 10, "Knight"))).copy(finalUrl = "$HOME_URL?menu=town"),
            NOW.plusSeconds(1),
        ))

        assertTrue(characters.findAllByAccountId(account.id).isEmpty())
        assertNull(statusSnapshots.findLatest(account.id)?.characterRosterObservedAt)
    }

    @Test
    fun `older home response cannot replace a newer roster`() {
        val account = account("roster-order")
        createStatusSnapshot(account)
        val newer = NOW.plusSeconds(10)
        assertTrue(observer.observe(
            account.id,
            homeResponse(rosterHtml(card("202", "최신", 20, "Mage"))),
            newer,
        ))

        assertFalse(observer.observe(
            account.id,
            homeResponse(rosterHtml(card("101", "과거", 10, "Knight"))),
            NOW,
        ))

        val actual = characters.findAllByAccountId(account.id).single()
        assertEquals("202", actual.hofCharacterId)
        assertEquals("최신", actual.name)
        assertEquals(newer, assertNotNull(statusSnapshots.findLatest(account.id)).characterRosterObservedAt)
    }

    @Test
    fun `roster projection waits until the account identity command fence is released`() {
        val account = account("roster-fenced")
        createStatusSnapshot(account)
        val commandStarted = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val observationAttempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val command = pool.submit {
                mutationFence.execute(account.id) {
                    commandStarted.countDown()
                    assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(commandStarted.await(1, TimeUnit.SECONDS))
            val observation = pool.submit<Boolean> {
                observationAttempted.countDown()
                observer.observe(
                    account.id,
                    homeResponse(rosterHtml(card("202", "최신", 20, "Mage"))),
                    NOW.plusSeconds(1),
                )
            }

            assertTrue(observationAttempted.await(1, TimeUnit.SECONDS))
            assertFalse(observation.isDone)
            releaseCommand.countDown()

            command.get(1, TimeUnit.SECONDS)
            assertTrue(observation.get(1, TimeUnit.SECONDS))
        } finally {
            releaseCommand.countDown()
            pool.shutdownNow()
        }
    }

    private fun createStatusSnapshot(account: HofAccountEntity) {
        assertTrue(statusSnapshots.observe(account.id, completeStatusHtml(), NOW.minusSeconds(1)))
    }

    private fun account(loginId: String): HofAccountEntity = accounts.save(
        HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = NOW),
    )

    private fun homeResponse(html: String) = HofHttpResponse(200, HOME_URL, html, emptyMap())

    private fun rosterHtml(vararg cards: String): String = """
        <html><body>
          <div id="menu2">사용자 · Funds : ${'$'} 100 · Time : 10/20</div>
          ${cards.joinToString("\n")}
        </body></html>
    """.trimIndent()

    private fun card(id: String, name: String, level: Int, job: String): String = """
        <div class="character-card">
          <a href="index.php?char=$id"><img src="$id.gif"></a><br>
          $name<br>
          Lv.$level $job
        </div>
    """.trimIndent()

    private fun completeStatusHtml(): String = """
        <table id="menu2">
          <tr>
            <td>사용자</td>
            <td>Funds : ${'$'} 100<br>Work : Nothing</td>
            <td>Time : 10/20<br>Auction : Nothing</td>
          </tr>
        </table>
    """.trimIndent()

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfig {
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object {
        const val HOME_URL = "https://hof.zerosic.com/index.php"
        val NOW: Instant = Instant.parse("2026-08-18T07:00:00Z")
    }
}
