package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.entity.QHofCookieEntity.hofCookieEntity
import app.spammy.hof.account.service.HofCookieCipher
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class CookieQueryRepository(
    private val queryFactory: JPAQueryFactory,
    private val cookieCipher: HofCookieCipher,
) {
    /** 시작 시 레거시 평문 쿠키를 암호화할 수 있도록 전체 쿠키를 안정적인 순서로 조회한다. */
    fun findAll(): List<HofCookieEntity> =
        queryFactory
            .selectFrom(hofCookieEntity)
            .orderBy(hofCookieEntity.id.asc())
            .fetch()

    /**
     * 계정 쿠키를 이름과 ID 오름차순으로 조회해 항상 결정적인 순서를 보장하며, 없으면 빈 목록을 반환한다.
     */
    fun findByAccountId(accountId: Long): List<HofCookieEntity> =
        queryFactory
            .selectFrom(hofCookieEntity)
            .where(hofCookieEntity.account.id.eq(accountId))
            .orderBy(hofCookieEntity.name.asc(), hofCookieEntity.id.asc())
            .fetch()

    /** 외부 HOF 요청에 사용할 때만 저장 암호문을 평문 쿠키 Map으로 복호화한다. */
    fun findValueMapByAccountId(accountId: Long): Map<String, String> =
        queryFactory
            .select(hofCookieEntity.name, hofCookieEntity.value)
            .from(hofCookieEntity)
            .where(hofCookieEntity.account.id.eq(accountId))
            .orderBy(hofCookieEntity.name.asc(), hofCookieEntity.id.asc())
            .fetch()
            .associate { tuple ->
                val name = requireNotNull(tuple.get(hofCookieEntity.name))
                val storedValue = requireNotNull(tuple.get(hofCookieEntity.value))
                name to cookieCipher.decrypt(storedValue)
            }
}
