package app.spammy.hof.captcha.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.QHofAccountEntity.hofAccountEntity
import app.spammy.hof.automation.convergence.QAccountBattleGateEntity.accountBattleGateEntity
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaFormFieldEntity
import app.spammy.hof.captcha.entity.QCaptchaChallengeEntity.captchaChallengeEntity
import app.spammy.hof.captcha.entity.QCaptchaFormFieldEntity.captchaFormFieldEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository

/**
 * 캡차 challenge와 form field의 모든 SELECT·COUNT를 QueryDSL로 수행한다.
 *
 * challenge 조회에는 계정 소유권과 필요한 상태 조건을 SQL에 함께 넣어 다른 계정의 row가 서비스로
 * 노출되지 않게 한다. form field는 challenge와 [CaptchaFormFieldEntity.fieldOrder] 순으로 읽어 HTML
 * input 순서가 제출 Map을 조립하는 단계까지 결정적으로 유지되게 한다.
 */
@Repository
class CaptchaQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /**
     * 계정 row에 비관적 쓰기 잠금을 걸어 같은 계정의 캡차 감지 트랜잭션을 commit까지 직렬화한다.
     *
     * pending row가 아직 없는 최초 감지는 challenge 자체를 잠글 수 없으므로 항상 존재하는 계정 row를
     * 잠금 경계로 사용한다. 이 잠금을 얻은 뒤 pending 조회·저장·교체를 수행하면 여러 서버 스레드나
     * 프로세스에서도 계정마다 하나의 pending challenge만 만들 수 있다.
     */
    fun findAccountByIdForUpdate(accountId: Long): HofAccountEntity? =
        queryFactory
            .selectFrom(hofAccountEntity)
            .where(hofAccountEntity.id.eq(accountId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

    /**
     * 계정의 가장 최근 pending challenge를 조회한다.
     *
     * 생성 시각이 같은 경우 큰 ID를 우선하는 tie-breaker를 두어 현재 challenge 선택이 흔들리지 않는다.
     */
    fun findLatestActiveByAccountId(accountId: Long): CaptchaChallengeEntity? =
        queryFactory
            .selectFrom(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.account.id.eq(accountId),
                captchaChallengeEntity.status.`in`(ACTIVE_STATUSES),
            )
            .orderBy(captchaChallengeEntity.createdAt.desc(), captchaChallengeEntity.id.desc())
            .fetchFirst()

    /** 계정의 pending challenge를 최신순으로 모두 읽어 재사용 대상과 과거 중복 row를 함께 찾는다. */
    fun findActiveByAccountId(accountId: Long): List<CaptchaChallengeEntity> =
        queryFactory
            .selectFrom(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.account.id.eq(accountId),
                captchaChallengeEntity.status.`in`(ACTIVE_STATUSES),
            )
            .orderBy(captchaChallengeEntity.createdAt.desc(), captchaChallengeEntity.id.desc())
            .fetch()

    fun findPendingAutomationResumes(accountId: Long): List<CaptchaChallengeEntity> =
        queryFactory.selectFrom(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.account.id.eq(accountId),
                captchaChallengeEntity.status.eq("ANSWERED"),
                captchaChallengeEntity.automationResumePending.isTrue,
            )
            .fetch()

    fun findPendingAutomationResumeAccountIds(): List<Long> =
        queryFactory.select(captchaChallengeEntity.account.id).distinct()
            .from(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.status.eq("ANSWERED"),
                captchaChallengeEntity.automationResumePending.isTrue,
            )
            .fetch()

    /** 유효 통행증 재관측에서만 사용한다. 답안 처리보다 늦게 생성된 관문도 재조정한다. */
    fun findLatestAnsweredWithActiveBattleGate(accountId: Long): CaptchaChallengeEntity? =
        queryFactory.select(captchaChallengeEntity)
            .from(captchaChallengeEntity, accountBattleGateEntity)
            .where(
                captchaChallengeEntity.account.id.eq(accountId),
                captchaChallengeEntity.status.eq("ANSWERED"),
                accountBattleGateEntity.accountId.eq(accountId),
                accountBattleGateEntity.resolvedAt.isNull,
            )
            .orderBy(captchaChallengeEntity.createdAt.desc(), captchaChallengeEntity.id.desc())
            .fetchFirst()

    /** 테스트와 운영 무결성 확인을 위해 계정의 pending challenge 수를 DB에서 계산한다. */
    fun countActiveByAccountId(accountId: Long): Long =
        queryFactory
            .select(captchaChallengeEntity.count())
            .from(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.account.id.eq(accountId),
                captchaChallengeEntity.status.`in`(ACTIVE_STATUSES),
            )
            .fetchOne() ?: 0L

    /** PK와 계정 FK를 한 조건으로 비교해 소유하지 않은 challenge도 찾지 못한 것으로 처리한다. */
    fun findOwnedByAccountIdAndId(
        accountId: Long,
        challengeId: Long,
    ): CaptchaChallengeEntity? =
        queryFactory
            .selectFrom(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.id.eq(challengeId),
                captchaChallengeEntity.account.id.eq(accountId),
            )
            .fetchOne()

    /**
     * 답안 제출 전에 소유권을 확인하면서 challenge row에 비관적 쓰기 잠금을 건다.
     *
     * 잠금 획득 후 서비스가 status를 검사하므로 첫 제출이 HOF 호출과 ANSWERED commit을 끝낼 때까지
     * 두 번째 제출은 대기하고, 이후 갱신된 status를 보고 외부 HOF 호출 전에 거절된다.
     */
    fun findOwnedByAccountIdAndIdForUpdate(
        accountId: Long,
        challengeId: Long,
    ): CaptchaChallengeEntity? =
        queryFactory
            .selectFrom(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.id.eq(challengeId),
                captchaChallengeEntity.account.id.eq(accountId),
            )
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

    /** 이미지처럼 특정 상태에서만 허용되는 경로는 소유권과 상태를 같은 SQL에서 검증한다. */
    fun findOwnedByAccountIdAndIdAndStatus(
        accountId: Long,
        challengeId: Long,
        status: String,
    ): CaptchaChallengeEntity? =
        queryFactory
            .selectFrom(captchaChallengeEntity)
            .where(
                captchaChallengeEntity.id.eq(challengeId),
                captchaChallengeEntity.account.id.eq(accountId),
                captchaChallengeEntity.status.eq(status),
            )
            .fetchOne()

    /** challenge 하나의 form field를 원본 input 순서로 반환한다. */
    fun findFormFields(challengeId: Long): List<CaptchaFormFieldEntity> =
        findFormFieldsByChallengeIds(listOf(challengeId))

    /**
     * 여러 challenge의 form field를 한 번에 읽고 challenge ID, 원본 input 순서, row ID 순으로 정렬한다.
     *
     * row ID는 잘못된 중복 field order가 들어와도 결과 순서를 고정하는 마지막 tie-breaker다.
     */
    fun findFormFieldsByChallengeIds(challengeIds: Collection<Long>): List<CaptchaFormFieldEntity> {
        if (challengeIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(captchaFormFieldEntity)
            .where(captchaFormFieldEntity.challenge.id.`in`(challengeIds))
            .orderBy(
                captchaFormFieldEntity.challenge.id.asc(),
                captchaFormFieldEntity.fieldOrder.asc(),
                captchaFormFieldEntity.id.asc(),
            )
            .fetch()
    }

    /** 테스트와 무결성 확인을 위해 challenge에 연결된 form field row 수를 DB에서 계산한다. */
    fun countFormFields(challengeId: Long): Long =
        queryFactory
            .select(captchaFormFieldEntity.count())
            .from(captchaFormFieldEntity)
            .where(captchaFormFieldEntity.challenge.id.eq(challengeId))
            .fetchOne() ?: 0L

    private companion object {
        val ACTIVE_STATUSES = listOf("DETECTED", "READY")
    }
}
