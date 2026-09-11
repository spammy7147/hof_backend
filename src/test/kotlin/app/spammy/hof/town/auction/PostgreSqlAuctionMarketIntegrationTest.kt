package app.spammy.hof.town.auction

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class PostgreSqlAuctionMarketIntegrationTest : AuctionMarketIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("HOF_TEST_POSTGRES_URL") }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.username") { "hof_test" }
            registry.add("spring.datasource.password") { "local-fixture-only" }
        }
    }
}
