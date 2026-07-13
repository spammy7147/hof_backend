package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.entity.QHofCookieEntity.hofCookieEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class CookieQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /**
     * 계정 쿠키를 이름과 ID 오름차순으로 조회해 항상 결정적인 순서를 보장하며, 없으면 빈 목록을 반환한다.
     */
    fun findByAccountId(accountId: Long): List<HofCookieEntity> =
        queryFactory
            .selectFrom(hofCookieEntity)
            .where(hofCookieEntity.account.id.eq(accountId))
            .orderBy(hofCookieEntity.name.asc(), hofCookieEntity.id.asc())
            .fetch()
}
