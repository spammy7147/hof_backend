package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.QHofAccountEntity.hofAccountEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
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
     * 계정 단위 자식 aggregate를 최초 생성할 때 사용할 쓰기 잠금 조회다.
     *
     * 아직 잠글 자식 행이 없는 최초 요청도 계정 부모 행에서 직렬화할 수 있다. 호출자는 이 잠금을
     * 획득한 뒤 자식 존재 여부를 다시 확인해야 중복 생성 경쟁을 피할 수 있다.
     */
    fun findByIdForUpdate(id: Long): HofAccountEntity? =
        queryFactory
            .selectFrom(hofAccountEntity)
            .where(hofAccountEntity.id.eq(id))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
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
