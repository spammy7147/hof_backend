package app.spammy.hof.common.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
/** 모든 기능 query repository가 같은 EntityManager transaction 문맥을 쓰도록 QueryDSL factory를 제공한다. */
class QueryDslConfig {
    @Bean
    /** 현재 request·service transaction의 EntityManager를 사용하는 QueryDSL 진입점을 생성한다. */
    fun jpaQueryFactory(entityManager: EntityManager): JPAQueryFactory =
        JPAQueryFactory(entityManager)
}
