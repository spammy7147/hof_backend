package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.QHofAccountEntity.hofAccountEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class AccountQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /**
     * 기본 키가 일치하는 HOF 계정을 조회하며, 저장된 계정이 없으면 `null`을 반환한다.
     */
    fun findById(id: Long): HofAccountEntity? =
        queryFactory
            .selectFrom(hofAccountEntity)
            .where(hofAccountEntity.id.eq(id))
            .fetchOne()

    /**
     * 로그인 ID가 일치하는 HOF 계정을 조회하며, 등록된 계정이 없으면 `null`을 반환한다.
     */
    fun findByLoginId(loginId: String): HofAccountEntity? =
        queryFactory
            .selectFrom(hofAccountEntity)
            .where(hofAccountEntity.loginId.eq(loginId))
            .fetchOne()

}
