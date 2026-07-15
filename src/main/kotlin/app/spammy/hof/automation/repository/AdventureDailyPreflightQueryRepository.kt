package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AdventureDailyPreflightStateEntity
import app.spammy.hof.automation.entity.QAdventureDailyPreflightStateEntity.adventureDailyPreflightStateEntity
import app.spammy.hof.automation.entity.QAdventureDailyRefreshEntity.adventureDailyRefreshEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.LocalDate
import org.springframework.stereotype.Repository

@Repository
class AdventureDailyPreflightQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun hasSuccessfulRefresh(accountId: Long, refreshDate: LocalDate): Boolean =
        queryFactory
            .selectOne()
            .from(adventureDailyRefreshEntity)
            .where(
                adventureDailyRefreshEntity.account.id.eq(accountId),
                adventureDailyRefreshEntity.refreshDate.eq(refreshDate),
            )
            .fetchFirst() != null

    fun findState(accountId: Long): AdventureDailyPreflightStateEntity? =
        queryFactory
            .selectFrom(adventureDailyPreflightStateEntity)
            .where(adventureDailyPreflightStateEntity.account.id.eq(accountId))
            .fetchOne()
}
