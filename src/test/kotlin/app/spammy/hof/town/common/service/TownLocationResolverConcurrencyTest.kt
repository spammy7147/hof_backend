package app.spammy.hof.town.common.service

import app.spammy.hof.town.common.model.TownFeatureId
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class TownLocationResolverConcurrencyTest {
    @Autowired
    private lateinit var resolver: TownLocationResolver

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `concurrent first discovery serializes on the preseeded feature row`() {
        jdbcTemplate.update(
            "update town_feature_locations set href = null, observed_at = null where feature_id = ?",
            TownFeatureId.PANTHEON.name,
        )
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf("firstPantheon", "secondPantheon").map { menu ->
                Callable {
                    start.await()
                    resolver.resolve(
                        TownFeatureId.PANTHEON,
                        "<a href='?menu=$menu'>신전 거리(Pantheon)</a>",
                    ).url
                }
            }
            val futures = tasks.map(pool::submit)
            start.countDown()
            val urls = futures.map { it.get() }

            assertEquals(1, urls.toSet().size)
            val row = jdbcTemplate.queryForMap(
                "select href, observed_at from town_feature_locations where feature_id = ?",
                TownFeatureId.PANTHEON.name,
            )
            assertEquals(urls.first().substringAfter("index.php"), row["href"])
            assertNotNull(row["observed_at"])
        } finally {
            pool.shutdownNow()
        }
    }
}
