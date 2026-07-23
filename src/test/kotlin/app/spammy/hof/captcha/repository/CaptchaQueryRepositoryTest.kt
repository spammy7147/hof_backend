package app.spammy.hof.captcha.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaFormFieldEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, CaptchaQueryRepository::class)
class CaptchaQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var challengeRepository: CaptchaChallengeRepository

    @Autowired
    private lateinit var formFieldRepository: CaptchaFormFieldCommandRepository

    @Autowired
    private lateinit var queryRepository: CaptchaQueryRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun readsLatestActiveAndOwnedChallengeWithoutCrossingAccountBoundary() {
        val owner = savedAccount("captcha-query-owner")
        val other = savedAccount("captcha-query-other")
        val olderDetected = savedChallenge(owner, "DETECTED", CREATED_AT)
        savedChallenge(owner, "ANSWERED", CREATED_AT.plusSeconds(30))
        val latestReady = savedChallenge(owner, "READY", CREATED_AT.plusSeconds(20))
        val foreignReady = savedChallenge(other, "READY", CREATED_AT.plusSeconds(40))

        assertEquals(latestReady.id, assertNotNull(queryRepository.findLatestActiveByAccountId(owner.id)).id)
        assertEquals(owner.id, assertNotNull(queryRepository.findAccountByIdForUpdate(owner.id)).id)
        assertEquals(
            listOf(latestReady.id, olderDetected.id),
            queryRepository.findActiveByAccountId(owner.id).map { it.id },
        )
        assertEquals(2L, queryRepository.countActiveByAccountId(owner.id))
        assertEquals(latestReady.id, assertNotNull(queryRepository.findOwnedByAccountIdAndId(owner.id, latestReady.id)).id)
        assertEquals(
            latestReady.id,
            assertNotNull(queryRepository.findOwnedByAccountIdAndIdForUpdate(owner.id, latestReady.id)).id,
        )
        assertEquals(
            latestReady.id,
            assertNotNull(queryRepository.findOwnedByAccountIdAndIdAndStatus(owner.id, latestReady.id, "READY")).id,
        )
        assertNull(queryRepository.findOwnedByAccountIdAndId(other.id, latestReady.id))
        assertNull(queryRepository.findOwnedByAccountIdAndIdAndStatus(owner.id, olderDetected.id, "READY"))
        assertNull(queryRepository.findLatestActiveByAccountId(Long.MAX_VALUE))
        assertEquals(foreignReady.id, assertNotNull(queryRepository.findLatestActiveByAccountId(other.id)).id)
    }

    @Test
    fun readsFieldsForChallengeIdsInSubmissionOrderAndCountsRows() {
        val account = savedAccount("captcha-query-fields")
        val first = savedChallenge(account, "PENDING", CREATED_AT)
        val second = savedChallenge(account, "PENDING", CREATED_AT.plusSeconds(1))
        formFieldRepository.saveAll(
            listOf(
                field(second, 1, "AnswerV", ""),
                field(first, 2, "AnswerOut", "입니다."),
                field(first, 0, "token", "abc"),
                field(second, 0, "next_token", "xyz"),
                field(first, 1, "AnswerV", ""),
            ),
        )

        assertEquals(
            listOf("token", "AnswerV", "AnswerOut"),
            queryRepository.findFormFields(first.id).map { it.fieldName },
        )
        assertEquals(
            listOf(
                first.id to "token",
                first.id to "AnswerV",
                first.id to "AnswerOut",
                second.id to "next_token",
                second.id to "AnswerV",
            ),
            queryRepository.findFormFieldsByChallengeIds(listOf(second.id, first.id))
                .map { it.challenge.id to it.fieldName },
        )
        assertEquals(3L, queryRepository.countFormFields(first.id))
        assertEquals(emptyList(), queryRepository.findFormFieldsByChallengeIds(emptyList()))
    }

    @Test
    fun deletingChallengeCascadesToFormFields() {
        val account = savedAccount("captcha-query-cascade")
        val challenge = savedChallenge(account, "PENDING", CREATED_AT)
        formFieldRepository.save(field(challenge, 0, "AnswerV", ""))
        formFieldRepository.flush()

        jdbcTemplate.update("delete from captcha_challenges where id = ?", challenge.id)

        assertEquals(0L, queryRepository.countFormFields(challenge.id))
    }

    @Test
    fun databaseRejectsDuplicateFieldNameWithinChallenge() {
        val account = savedAccount("captcha-query-unique")
        val challenge = savedChallenge(account, "PENDING", CREATED_AT)
        formFieldRepository.save(field(challenge, 0, "AnswerV", ""))
        formFieldRepository.flush()

        kotlin.test.assertFailsWith<DataIntegrityViolationException> {
            formFieldRepository.save(field(challenge, 1, "AnswerV", "duplicate"))
            formFieldRepository.flush()
        }
    }

    @Test
    fun captchaPersistenceContainsNoJacksonOrLegacyFormJson() {
        val captchaRoot = Path.of("src/main/kotlin/app/spammy/hof/captcha")
        val violations = Files.walk(captchaRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path -> path.toString().endsWith(".kt") }
                .filter { path ->
                    val source = Files.readString(path)
                    source.contains("tools.jackson") ||
                        source.contains("JsonMapper") ||
                        source.contains("formFieldsJson")
                }
                .map(Path::toString)
                .toList()
        }

        assertTrue(violations.isEmpty(), "captcha JSON persistence violations: $violations")
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = CREATED_AT,
            ),
        )

    private fun savedChallenge(
        account: HofAccountEntity,
        status: String,
        createdAt: Instant,
    ): CaptchaChallengeEntity =
        challengeRepository.save(
            CaptchaChallengeEntity(
                account = account,
                status = status,
                prompt = "캡차를 입력해주세요.",
                imageUrl = "http://sic.zerosic.com/ZeroHOF/captcha.png",
                sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
                answer = null,
                createdAt = createdAt,
                answeredAt = null,
                submitUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
                submitMethod = "POST",
                answerFieldName = "AnswerV",
            ),
        )

    private fun field(
        challenge: CaptchaChallengeEntity,
        order: Int,
        name: String,
        value: String,
    ): CaptchaFormFieldEntity =
        CaptchaFormFieldEntity(
            challenge = challenge,
            fieldOrder = order,
            fieldName = name,
            fieldValue = value,
        )

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
