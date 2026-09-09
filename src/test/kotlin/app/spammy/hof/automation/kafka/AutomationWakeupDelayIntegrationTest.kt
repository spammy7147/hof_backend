package app.spammy.hof.automation.kafka

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.config.AutomationKafkaConfig
import app.spammy.hof.automation.dto.UpdateFishingAutomationRequest
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.service.AutomationDailyPreflight
import app.spammy.hof.automation.service.AutomationRecoveryIntegrationTest
import app.spammy.hof.automation.service.UnifiedAutomationRunner
import app.spammy.hof.automation.service.UnifiedAutomationService
import app.spammy.hof.automation.service.TypedAutomationLifecycleBridge
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@Import(AutomationWakeupDelayIntegrationTest.Config::class)
abstract class AutomationWakeupDelayIntegrationTest {
    @Autowired private lateinit var runner: UnifiedAutomationRunner
    @Autowired private lateinit var controls: UnifiedAutomationService
    @Autowired private lateinit var lifecycle: TypedAutomationLifecycleBridge
    @Autowired private lateinit var consumed: AutomationConsumedEventService
    @Autowired private lateinit var lease: AccountAutomationLeaseService
    @Autowired private lateinit var outbox: AutomationOutboxService
    @Autowired private lateinit var outboxQuery: AutomationOutboxQueryRepository
    @Autowired private lateinit var marker: AutomationOutboxPublishMarker
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader

    @Test
    fun `느린 HOF 판단을 여러 wake가 깨워도 한 poll의 누적으로 재할당되지 않는다`() {
        val scenario = createScenario()
        val result = consumeBacklog(scenario, 5)
        assertEquals(listOf("FStart", "FCatch"), scenario.posts())
        assertTrue(result.cycles > 1, "후속 wake도 실제 새 판단에 도달해야 한다.")
        assertTrue(result.processingMillis.all { it < 5000 }, "개별 판단 지연과 poll 누적 지연을 구분해야 한다.")
        assertEquals(0, result.revocations, "개별 판단은 poll 예산 안에 끝나지만 같은 poll의 누적 처리로 실행 중 재할당되면 안 된다.")
    }

    @Test
    fun `서로 다른 due 계정의 판단 누적도 모델 시계 진행 없이 재할당을 일으키지 않는다`() {
        val scenarios = List(4) { createScenario() }
        val result = consumeBacklog(scenarios.first(), 4, otherScenarios = scenarios.drop(1), advanceModelClock = false)
        scenarios.forEach { assertEquals(listOf("FStart", "FCatch"), it.posts()) }
        assertTrue(result.processingMillis.all { it < 5000 })
        assertTrue(result.processingMillis.sum() > 5000, "여러 계정의 실제 처리 누적이 poll 예산을 넘는 조건이어야 한다.")
        assertEquals(0, result.revocations)
    }

    @Test
    fun `미래 재확인 wake는 일찍 발행하지 않고 소비 container 재시작 뒤 다음 판단으로 이어진다`() {
        val scenario = createScenario()
        val result = consumeBacklog(scenario, 2, restartForFutureWake = true)
        assertEquals(listOf("FStart", "FCatch"), scenario.posts(), "재시작과 미래 wake가 이미 적용한 낚시를 다시 제출하지 않는다.")
        assertTrue(result.cycles > 2)
        assertEquals(0, result.revocations)
    }

    @Test
    fun `과거 due wake가 최신 설정과 재개 의도를 다음 판단에 반영하고 사용자 중단을 지킨다`() {
        val scenario = createScenario(enabled = false)
        var intentStarted = 0L
        val intentMillis = mutableMapOf<String, Long>()
        val eventAgeSeconds = mutableMapOf<String, Long>()
        fun applied(name: String, event: AutomationWakeupEvent) {
            intentMillis[name] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - intentStarted)
            eventAgeSeconds[name] = java.time.Duration.between(Instant.parse(event.createdAt), clock.now()).seconds
        }
        val result = consumeBacklog(scenario, 8) { index, event ->
            assertEquals("DUE_RECOVERY", event.reason, "새 제어 event보다 기존 backlog가 최신 DB 의도를 반영한다.")
            when (index) {
                1 -> {
                    assertEquals(emptyList(), scenario.posts())
                    controls.updateFishing(scenario.accountId, UpdateFishingAutomationRequest(enabled = true))
                    intentStarted = System.nanoTime()
                }
                2 -> {
                    assertEquals(listOf("FStart", "FCatch"), scenario.posts())
                    applied("settings", event)
                    controls.pauseTyped(scenario.accountId)
                }
                3 -> {
                    assertEquals(2, scenario.posts().size, "pause 동안 새 행동을 제출하지 않는다.")
                    scenario.resetFishing()
                    controls.resumeTyped(scenario.accountId)
                    intentStarted = System.nanoTime()
                }
                4 -> {
                    assertEquals(4, scenario.posts().size)
                    applied("resume", event)
                    TransactionTemplate(transactions).executeWithoutResult {
                        lifecycle.suspendForAuthentication(scenario.accountId, "TEST_AUTH_END")
                    }
                    Mockito.`when`(authorization.isExecutionAllowed(scenario.accountId)).thenReturn(false)
                }
                5 -> {
                    assertEquals(4, scenario.posts().size, "인증 중단 동안 새 행동을 제출하지 않는다.")
                    Mockito.`when`(authorization.isExecutionAllowed(scenario.accountId)).thenReturn(true)
                    scenario.resetFishing()
                    TransactionTemplate(transactions).executeWithoutResult {
                        assertTrue(lifecycle.resumeAfterAuthentication(scenario.accountId, "TEST_AUTH_RESTORED"))
                    }
                    intentStarted = System.nanoTime()
                }
                6 -> {
                    assertEquals(6, scenario.posts().size)
                    applied("auth-resume", event)
                    controls.stopTyped(scenario.accountId)
                }
                7 -> {
                    assertEquals(6, scenario.posts().size, "사용자 stop 동안 새 행동을 제출하지 않는다.")
                    scenario.resetFishing()
                    controls.startTyped(scenario.accountId)
                    intentStarted = System.nanoTime()
                }
                8 -> {
                    assertEquals(8, scenario.posts().size)
                    applied("start", event)
                }
            }
        }
        println("AUTO-04 의도 반영 실제ms=$intentMillis, wake age 모델초=$eventAgeSeconds")
        assertEquals(List(4) { listOf("FStart", "FCatch") }.flatten(), scenario.posts())
        assertEquals(setOf("settings", "resume", "auth-resume", "start"), intentMillis.keys)
        assertTrue(intentMillis.values.all { it < 5000 }, "최신 의도가 다음 한 판단의 예산 안에서 실제 제출로 이어져야 한다: $intentMillis")
        assertTrue(eventAgeSeconds.values.all { it >= 20 }, "모델 시계의 오래된 wake age와 실제 의도 반영 시간을 구분한다.")
        assertEquals(0, result.revocations)
    }

    private data class Scenario(
        val accountId: Long,
        val requests: CopyOnWriteArrayList<HofRequest>,
        val resetFishing: () -> Unit,
    ) {
        fun posts() = requests.mapNotNull { request -> listOf("FStart", "FCatch").singleOrNull { it in request.formFields } }
    }

    private data class KafkaResult(val processingMillis: List<Long>, val revocations: Int, val cycles: Int)

    private fun createScenario(enabled: Boolean = true): Scenario {
        clock.current = Instant.parse("2026-09-10T00:00:00Z")
        val accountId = TransactionTemplate(transactions).execute {
            val account = HofAccountEntity(loginId = "wake-delay-${UUID.randomUUID()}", encryptedPassword = "test", createdAt = clock.now())
            entityManager.persist(account)
            entityManager.flush()
            entityManager.persist(AutomationEntryEntity(account = account, type = AutomationType.FISHING, priority = 0,
                singletonTypeMarker = AutomationType.FISHING, enabled = enabled, createdAt = clock.now(), updatedAt = clock.now()))
            entityManager.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "test", updatedAt = clock.now()))
            entityManager.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
            entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = clock.now(), updatedAt = clock.now()))
            account.id
        }
        Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
        val requests = CopyOnWriteArrayList<HofRequest>()
        var phase = "reset"
        val header = """<table id='menu2'><tr><td>《테스트》테스트</td><td>Funds : $ 1<br>Work : Nothing</td>
            <td>Time : 100/100<br>Auction : Nothing</td></tr></table>"""
        fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$name.html")).readText()
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            Thread.sleep(400) // 실제 HOF adapter의 지연만 축소해 poll 누적을 재현한다.
            val body = when {
                "FStart" in request.formFields -> { phase = "waiting"; fixture("waiting") }
                "FCatch" in request.formFields -> { phase = "exhausted"; fixture("caught") }
                request.url.contains("menu=fishing") -> if (phase == "exhausted") fixture("reset").replace("18회", "0회") else fixture(phase)
                else -> """<div id='contents'><div id='mapgroup1'><a href='index.php?common=0001'>일반 맵</a></div></div>
                    <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6><img src='image/zerohof.gif'></div>"""
            }
            HofHttpResponse(200, request.url, header + body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.any<HofRequest>()
            ?: HofRequest(HofHttpMethod.GET, "https://example.test"), Mockito.anyMap())

        return Scenario(accountId, requests) { phase = "reset" }
    }

    private fun consumeBacklog(
        scenario: Scenario,
        count: Int,
        otherScenarios: List<Scenario> = emptyList(),
        advanceModelClock: Boolean = true,
        restartForFutureWake: Boolean = false,
        afterConsume: (Int, AutomationWakeupEvent) -> Unit = { _, _ -> },
    ): KafkaResult {
        val accountId = scenario.accountId
        val scenarios = listOf(scenario) + otherScenarios
        val broker = EmbeddedKafkaKraftBroker(1, 1, AutomationOutboxService.WAKEUP_TOPIC)
        broker.brokerListProperty("hof.test.auto04.brokers")
        broker.brokerProperties(mapOf("group.initial.rebalance.delay.ms" to "0"))
        broker.afterPropertiesSet()
        val group = "auto04-${UUID.randomUUID()}"
        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to 5000,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 500,
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG to 6000,
        ))
        val factory = AutomationKafkaConfig().automationKafkaListenerFactory(consumerFactory)
        val container = factory.createContainer(AutomationOutboxService.WAKEUP_TOPIC)
        // 한 계정의 한 partition에서 poll 누적을 검증한다. 초기 빈 consumer의 그룹 합류는 제외한다.
        container.concurrency = 1
        val watchRevocations = AtomicBoolean(false)
        val revoked = AtomicInteger()
        val completed = ConcurrentHashMap.newKeySet<String>()
        val completion = CountDownLatch(count)
        val futureCompletion = CountDownLatch(1)
        val callbackFailure = AtomicReference<Throwable>()
        val processingMillis = CopyOnWriteArrayList<Long>()
        val consumeStarts = CopyOnWriteArrayList<Long>()
        val lastConsumeEnd = AtomicLong()
        val decisionWakeStarts = ConcurrentHashMap<Long, CopyOnWriteArrayList<Long>>()
        val consumer = AutomationWakeupConsumer(mapper, consumed, lease, runner, clock)
        container.containerProperties.pollTimeout = 50
        container.containerProperties.setConsumerRebalanceListener(object : ConsumerRebalanceListener {
            override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
                if (watchRevocations.get() && partitions.isNotEmpty()) revoked.incrementAndGet()
            }
            override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
                if (partitions.isNotEmpty()) watchRevocations.set(true)
            }
        })
        container.containerProperties.setMessageListener(AcknowledgingMessageListener<String, String> { record, acknowledgment ->
            val event = mapper.readValue(record.value(), AutomationWakeupEvent::class.java)
            val cyclesBefore = journal.page(event.accountId, AutomationHistoryQuery()).cycles.size
            // 기존 5초 due 주기보다 다음 판단 시각을 앞으로 진행한다. Kafka 시간은 실제 시계다.
            if (advanceModelClock) clock.current = clock.now().plusSeconds(10)
            val started = System.nanoTime()
            consumeStarts += started
            try {
                consumer.consume(record.value(), assertNotNull(acknowledgment))
            } finally {
                val ended = System.nanoTime()
                lastConsumeEnd.set(ended)
                processingMillis += TimeUnit.NANOSECONDS.toMillis(ended - started)
                if (journal.page(event.accountId, AutomationHistoryQuery()).cycles.size > cyclesBefore) {
                    decisionWakeStarts.computeIfAbsent(event.accountId) { CopyOnWriteArrayList() }.add(started)
                }
            }
            if (completed.add(event.eventId)) {
                try {
                    afterConsume(completed.size, event)
                } catch (error: Throwable) {
                    callbackFailure.compareAndSet(null, error)
                } finally {
                    completion.countDown()
                    if (event.reason == "FUTURE_RECHECK") futureCompletion.countDown()
                }
            }
        })
        val producer = DefaultKafkaProducerFactory<String, String>(mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.brokersAsString,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ))
        try {
            // 소비 시작 전에 모두 발행해 한 poll이 읽을 backlog를 만든다.
            val events = (0 until count).map { outbox.enqueue(scenarios[it % scenarios.size].accountId, "DUE_RECOVERY") }
            val future = if (restartForFutureWake) outbox.enqueue(accountId, "FUTURE_RECHECK", clock.now().plusSeconds(3600)) else null
            val publisher = AutomationOutboxPublisher(outboxQuery, marker, KafkaAutomationOutboxTransport(KafkaTemplate(producer)), clock)
            val publishStarted = System.nanoTime()
            publisher.publishBatch()
            val publishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - publishStarted)
            assertEquals(count, events.count { outboxQuery.findById(it.id)?.publishedAt != null })
            future?.let { assertNull(outboxQuery.findById(it.id)?.publishedAt) }
            container.start()
            assertTrue(completion.await(35, TimeUnit.SECONDS), "같은 Kafka 진입점에서 모든 due wake를 소비해야 한다: ${completed.size}, 시간=$processingMillis")
            callbackFailure.get()?.let { throw AssertionError("Kafka 소비 이후 제어 검증 실패", it) }
            assertTrue(events.all { consumed.wasConsumed(it.eventId) })
            if (future != null) {
                assertTrue(clock.now().isBefore(future.availableAt))
                assertFalse(consumed.wasConsumed(future.eventId))
                assertNull(outboxQuery.findById(future.id)?.publishedAt)
                val cyclesBeforeRestart = journal.page(accountId, AutomationHistoryQuery()).cycles.size
                watchRevocations.set(false)
                container.stop()
                clock.current = future.availableAt
                publisher.publishBatch()
                assertNotNull(outboxQuery.findById(future.id)?.publishedAt)
                container.start()
                assertTrue(futureCompletion.await(15, TimeUnit.SECONDS), "재시작한 실제 consumer가 due가 된 미래 wake를 소비해야 한다.")
                watchRevocations.set(false)
                container.stop()
                assertTrue(consumed.wasConsumed(future.eventId))
                assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > cyclesBeforeRestart,
                    "미래 wake 등록뿐 아니라 재시작 뒤 실제 다음 판단을 검증한다.")
            }
            scenarios.forEach {
                assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, it.accountId))
            }
            println("AUTO-04 실제 Kafka: 소비=${completed.size}, 처리시간ms=$processingMillis, 재할당=${revoked.get()}")
            val consumeMillis = TimeUnit.NANOSECONDS.toMillis(lastConsumeEnd.get() - consumeStarts.first())
            val decisionWakeGaps = decisionWakeStarts.values.map { starts ->
                starts.zipWithNext { first, next -> TimeUnit.NANOSECONDS.toMillis(next - first) }
            }
            println("AUTO-04 유한 backlog: 최초발행=$count/${publishMillis}ms, 소비=${completed.size}/${consumeMillis}ms, 판단이 발생한 wake 시작간격ms=$decisionWakeGaps")
            return KafkaResult(processingMillis.toList(), revoked.get(), scenarios.sumOf { journal.page(it.accountId, AutomationHistoryQuery()).cycles.size })
        } finally {
            watchRevocations.set(false)
            container.stop()
            producer.destroy()
            broker.destroy()
            completed.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
            scenarios.forEach { jdbc.update("delete from hof_accounts where id = ?", it.accountId) }
            System.clearProperty("hof.test.auto04.brokers")
            System.clearProperty("spring.embedded.kafka.brokers")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean @Primary fun clock() = AutomationRecoveryIntegrationTest.RecoveryClock()
        @Bean @Primary fun wakeups(outbox: AutomationOutboxService): AutomationWakeupPort = KafkaAutomationWakeupAdapter(outbox)
    }
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationWakeupDelayIntegrationTest : AutomationWakeupDelayIntegrationTest()

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationWakeupDelayIntegrationTest : AutomationWakeupDelayIntegrationTest()

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationWakeupDelayIntegrationTest : AutomationWakeupDelayIntegrationTest()
