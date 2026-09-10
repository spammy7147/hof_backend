package app.spammy.hof.push.kafka

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** 부분 성공의 commit과 token 폐기를 실제 PostgreSQL에서도 같은 consumer interface로 확인한다. */
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class PostgreSqlPushDeliveryIntegrationTest : PushDeliveryIntegrationTest() {
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
