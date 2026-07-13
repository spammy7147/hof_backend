package app.spammy.hof.common.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull

@ActiveProfiles("test")
@SpringBootTest
class QueryDslConfigTest {
    @Autowired
    private lateinit var jpaQueryFactory: JPAQueryFactory

    @Test
    fun exposesJpaQueryFactory() {
        assertNotNull(jpaQueryFactory)
    }
}
