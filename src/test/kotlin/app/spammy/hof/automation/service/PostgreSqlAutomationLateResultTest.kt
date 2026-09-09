package app.spammy.hof.automation.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class PostgreSqlAutomationLateResultTest : AutomationLateResultIntegrationTest() {
    @Test
    fun `PostgreSQL에서도 rollback 후 제어 변경으로 수용되지 않은 START에 성공 이력을 쓰지 않는다`() =
        verifyCanonicalDirectRollback("FISHING_START", failAfterCallback = true, resumeAfterRollback = true)

    @Test
    fun `PostgreSQL에서도 관측 이력 DB 실패가 확정한 결과를 취소하지 않는다`() = verifyProbeHistoryFailure()

    @ParameterizedTest
    @ValueSource(strings = ["FISHING_START", "FISHING_CATCH", "FISHING_OBSTRUCTION_BATTLE"])
    fun `PostgreSQL 직접 증거 rollback은 원격 재제출 없이 DB 저장만 재시도한다`(kind: String) =
        verifyCanonicalDirectRollback(kind)

    @ParameterizedTest
    @ValueSource(strings = ["FISHING_CATCH", "FISHING_OBSTRUCTION_BATTLE"])
    fun `PostgreSQL callback 완료 뒤 commit 오류는 자동 재시도로 숨기지 않는다`(kind: String) =
        verifyCanonicalDirectRollback(kind, failAfterCallback = true)

    @Test
    fun `PostgreSQL에서도 직접 성공 뒤 stale probe 대체 이력을 기록하지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterRecoveryCompletes = true, staleHistory = true)

    @Test
    fun `PostgreSQL에서도 오래된 probe가 직접 적용을 덮어쓰지 않는다`() =
        verifyLateCatchWithFailedObservation("ADVANCED", afterRecoveryCompletes = true, staleObservation = true)

    @Test
    fun `PostgreSQL에서도 예산 만료 조회가 직접 적용을 보류로 되돌리지 않는다`() =
        verifyLateBattleWithAnotherWorker(afterPendingExpires = true, staleDue = true)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("HOF_TEST_POSTGRES_URL") }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.username") { "hof_test" }
            registry.add("spring.datasource.password") { "local-fixture-only" }
            registry.add("spring.datasource.hikari.connection-init-sql") { "SET lock_timeout = '15s'" }
        }
    }
}
