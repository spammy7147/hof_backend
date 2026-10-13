package app.spammy.hof.automation.service

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.external.model.HofHttpMethod
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class CharacterIdReplacementAutomationTest : AutomationRecoveryFixture() {
    @Autowired lateinit var identity: CharacterLifecycleService

    @Test
    fun `기존 프리셋 캐릭터 재연결 뒤 깨우기는 새 ID로 패턴과 전투를 제출하고 다음 판단을 실행한다`() {
        setupFishing(initialBattle = true)
        val target = TransactionTemplate(transactions).execute {
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.first()
        }
        identity.link(accountId, target.id, "knockback-new-id", CharacterHofIdLinkReason.KNOCKBACK, false)
        wakeups.wake(accountId, "KNOCKBACK_AUDIT")
        publisher.publishBatch()
        val pattern = requests.filter { it.method == HofHttpMethod.POST && it.url.contains("?char=") }
        assertTrue(pattern.isNotEmpty())
        assertTrue(pattern.all { it.url.contains("?char=knockback-new-id") })
        val battles = requests.filter { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
        assertEquals(1, battles.size)
        assertTrue(battles.single().formEntries.any { it.name == "char_knockback-new-id" })
        assertFalse(battles.single().formEntries.any { it.name == "char_${target.hofCharacterId}" })
        assertEquals(0, runningWorkCount())
        consumeNextWake()
        Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
        assertEquals(0, runningWorkCount())
    }
}
