package app.spammy.hof.battle.repository

import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.entity.QAccountBattleMapStateEntity.accountBattleMapStateEntity
import app.spammy.hof.battle.entity.QBattleMapAliasEntity.battleMapAliasEntity
import app.spammy.hof.battle.entity.QBattleMapEntity.battleMapEntity
import app.spammy.hof.battle.entity.QBattleMapGroupEntity.battleMapGroupEntity
import app.spammy.hof.battle.entity.QUnresolvedBattleMapEntity.unresolvedBattleMapEntity
import app.spammy.hof.battle.entity.UnresolvedBattleMapEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class BattleMapQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /** 카테고리와 HOF 맵 코드가 모두 일치하는 정적 카탈로그 맵을 조회한다. */
    fun findMapByCategoryIdAndMapCode(
        categoryId: String,
        mapCode: String,
    ): BattleMapEntity? =
        queryFactory
            .selectFrom(battleMapEntity)
            .leftJoin(battleMapEntity.group, battleMapGroupEntity).fetchJoin()
            .where(
                battleMapEntity.categoryId.eq(categoryId),
                battleMapEntity.mapCode.eq(mapCode),
            )
            .fetchOne()

    /**
     * 요청한 `(categoryId, mapCode)` 후보를 한 SELECT로 읽고 정확한 pair만 반환한다.
     *
     * SQL에서는 category와 code를 각각 `IN` 조건으로 제한하므로 교차 조합 행도 조회될 수 있다. 최종
     * 결과를 요청 pair 집합으로 다시 필터링해 호출자가 요청하지 않은 맵이 검증을 통과하지 않게 한다.
     */
    fun findMapsByCategoryIdAndMapCodePairs(
        candidates: Collection<Pair<String, String>>,
    ): List<BattleMapEntity> {
        val requestedPairs = candidates.toSet()
        if (requestedPairs.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(battleMapEntity)
            .leftJoin(battleMapEntity.group, battleMapGroupEntity).fetchJoin()
            .where(
                battleMapEntity.categoryId.`in`(requestedPairs.map { it.first }.toSet()),
                battleMapEntity.mapCode.`in`(requestedPairs.map { it.second }.toSet()),
            )
            .orderBy(
                battleMapEntity.categoryId.asc(),
                battleMapEntity.mapCode.asc(),
                battleMapEntity.id.asc(),
            )
            .fetch()
            .filter { battleMap -> battleMap.categoryId to battleMap.mapCode in requestedPairs }
    }

    /** 카테고리의 정적 맵 전체를 그룹과 함께 표시 순서로 조회한다. */
    fun findMapsByCategoryId(categoryId: String): List<BattleMapEntity> =
        queryFactory
            .selectFrom(battleMapEntity)
            .leftJoin(battleMapEntity.group, battleMapGroupEntity).fetchJoin()
            .where(battleMapEntity.categoryId.eq(categoryId))
            .orderBy(
                battleMapGroupEntity.displayOrder.asc(),
                battleMapEntity.displayOrder.asc(),
                battleMapEntity.name.asc(),
                battleMapEntity.id.asc(),
            )
            .fetch()

    /** 카테고리의 정적 그룹을 표시 순서와 이름으로 안정적으로 조회한다. */
    fun findGroupsByCategoryId(categoryId: String): List<BattleMapGroupEntity> =
        queryFactory
            .selectFrom(battleMapGroupEntity)
            .where(battleMapGroupEntity.categoryId.eq(categoryId))
            .orderBy(
                battleMapGroupEntity.displayOrder.asc(),
                battleMapGroupEntity.name.asc(),
                battleMapGroupEntity.id.asc(),
            )
            .fetch()

    /** 카테고리의 모든 DB 별칭을 맵과 그룹 정보와 함께 조회한다. */
    fun findAliasesByCategoryId(categoryId: String): List<BattleMapAliasEntity> =
        queryFactory
            .selectFrom(battleMapAliasEntity)
            .join(battleMapAliasEntity.battleMap, battleMapEntity).fetchJoin()
            .leftJoin(battleMapEntity.group, battleMapGroupEntity).fetchJoin()
            .where(battleMapEntity.categoryId.eq(categoryId))
            .orderBy(
                battleMapGroupEntity.displayOrder.asc(),
                battleMapEntity.displayOrder.asc(),
                battleMapEntity.id.asc(),
                battleMapAliasEntity.normalizedAlias.asc(),
                battleMapAliasEntity.id.asc(),
            )
            .fetch()

    /** 정규화한 관측 이름과 일치하는 카테고리 별칭을 중복 후보까지 그대로 조회한다. */
    fun findAliasesByCategoryIdAndNormalizedAliases(
        categoryId: String,
        normalizedAliases: Collection<String>,
    ): List<BattleMapAliasEntity> {
        if (normalizedAliases.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(battleMapAliasEntity)
            .join(battleMapAliasEntity.battleMap, battleMapEntity).fetchJoin()
            .leftJoin(battleMapEntity.group, battleMapGroupEntity).fetchJoin()
            .where(
                battleMapEntity.categoryId.eq(categoryId),
                battleMapAliasEntity.normalizedAlias.`in`(normalizedAliases),
            )
            .orderBy(
                battleMapGroupEntity.displayOrder.asc(),
                battleMapEntity.displayOrder.asc(),
                battleMapEntity.id.asc(),
                battleMapAliasEntity.normalizedAlias.asc(),
                battleMapAliasEntity.id.asc(),
            )
            .fetch()
    }

    /** 특정 카탈로그 맵에 이미 저장된 별칭을 정규화 값 순서로 조회한다. */
    fun findAliasesByMapId(mapId: Long): List<BattleMapAliasEntity> =
        queryFactory
            .selectFrom(battleMapAliasEntity)
            .join(battleMapAliasEntity.battleMap, battleMapEntity).fetchJoin()
            .where(battleMapEntity.id.eq(mapId))
            .orderBy(battleMapAliasEntity.normalizedAlias.asc(), battleMapAliasEntity.id.asc())
            .fetch()

    /** 계정과 카탈로그 맵이 모두 일치하는 동적 상태를 조회한다. */
    fun findStateByAccountIdAndMapId(
        accountId: Long,
        mapId: Long,
    ): AccountBattleMapStateEntity? =
        stateQuery()
            .where(
                accountBattleMapStateEntity.account.id.eq(accountId),
                battleMapEntity.id.eq(mapId),
            )
            .fetchOne()

    /** 새 관측을 적용하기 전 숨길 계정/카테고리의 모든 상태를 조회한다. */
    fun findStatesByAccountIdAndCategoryId(
        accountId: Long,
        categoryId: String,
    ): List<AccountBattleMapStateEntity> =
        stateQuery()
            .where(
                accountBattleMapStateEntity.account.id.eq(accountId),
                battleMapEntity.categoryId.eq(categoryId),
            )
            .orderBy(
                battleMapGroupEntity.displayOrder.asc(),
                battleMapEntity.displayOrder.asc(),
                battleMapEntity.name.asc(),
                battleMapEntity.id.asc(),
            )
            .fetch()

    /** 현재 보이는 계정/카테고리 상태를 정적 트리 순서로 조회한다. */
    fun findVisibleStatesByAccountIdAndCategoryId(
        accountId: Long,
        categoryId: String,
    ): List<AccountBattleMapStateEntity> =
        stateQuery()
            .where(
                accountBattleMapStateEntity.account.id.eq(accountId),
                battleMapEntity.categoryId.eq(categoryId),
                accountBattleMapStateEntity.visible.isTrue,
            )
            .orderBy(
                battleMapGroupEntity.displayOrder.asc(),
                battleMapEntity.displayOrder.asc(),
                battleMapEntity.name.asc(),
                battleMapEntity.id.asc(),
            )
            .fetch()

    /** 전투 실행 요청의 계정, 카테고리, 맵 코드에 해당하는 저장 상태를 조회한다. */
    fun findStateForExecution(
        accountId: Long,
        categoryId: String,
        mapCode: String,
    ): AccountBattleMapStateEntity? =
        stateQuery()
            .where(
                accountBattleMapStateEntity.account.id.eq(accountId),
                battleMapEntity.categoryId.eq(categoryId),
                battleMapEntity.mapCode.eq(mapCode),
            )
            .fetchOne()

    /**
     * 자동화 판단에 필요한 계정별 맵 상태를 `(categoryId, mapCode)` 집합으로 한 번에 조회한다.
     *
     * SQL의 두 `IN` 조건이 만들 수 있는 교차 조합은 결과에서 다시 제거해 요청하지 않은 상태가 실행
     * 후보로 섞이지 않게 한다.
     */
    fun findStatesForExecution(
        accountId: Long,
        candidates: Collection<Pair<String, String>>,
    ): List<AccountBattleMapStateEntity> {
        val requestedPairs = candidates.toSet()
        if (requestedPairs.isEmpty()) return emptyList()

        return stateQuery()
            .where(
                accountBattleMapStateEntity.account.id.eq(accountId),
                battleMapEntity.categoryId.`in`(requestedPairs.map { it.first }.toSet()),
                battleMapEntity.mapCode.`in`(requestedPairs.map { it.second }.toSet()),
            )
            .orderBy(battleMapEntity.categoryId.asc(), battleMapEntity.mapCode.asc(), battleMapEntity.id.asc())
            .fetch()
            .filter { state -> state.battleMap.categoryId to state.battleMap.mapCode in requestedPairs }
    }

    /** 계정/카테고리/그룹/이름 정규화 정체성이 일치하는 미해결 행을 조회한다. */
    fun findUnresolvedByIdentity(
        accountId: Long,
        categoryId: String,
        groupNormalizedName: String,
        normalizedName: String,
    ): UnresolvedBattleMapEntity? =
        queryFactory
            .selectFrom(unresolvedBattleMapEntity)
            .where(
                unresolvedBattleMapEntity.account.id.eq(accountId),
                unresolvedBattleMapEntity.categoryId.eq(categoryId),
                unresolvedBattleMapEntity.groupNormalizedName.eq(groupNormalizedName),
                unresolvedBattleMapEntity.normalizedName.eq(normalizedName),
            )
            .fetchOne()

    /** 새 관측을 적용하기 전 숨길 계정/카테고리의 모든 미해결 행을 조회한다. */
    fun findUnresolvedByAccountIdAndCategoryId(
        accountId: Long,
        categoryId: String,
    ): List<UnresolvedBattleMapEntity> =
        unresolvedQuery(accountId, categoryId, visibleOnly = false)

    /** 현재 보이는 계정/카테고리 미해결 행을 원본 트리 순서로 조회한다. */
    fun findVisibleUnresolvedByAccountIdAndCategoryId(
        accountId: Long,
        categoryId: String,
    ): List<UnresolvedBattleMapEntity> =
        unresolvedQuery(accountId, categoryId, visibleOnly = true)

    private fun stateQuery() =
        queryFactory
            .selectFrom(accountBattleMapStateEntity)
            .join(accountBattleMapStateEntity.battleMap, battleMapEntity).fetchJoin()
            .leftJoin(battleMapEntity.group, battleMapGroupEntity).fetchJoin()

    private fun unresolvedQuery(
        accountId: Long,
        categoryId: String,
        visibleOnly: Boolean,
    ): List<UnresolvedBattleMapEntity> {
        val query = queryFactory
            .selectFrom(unresolvedBattleMapEntity)
            .where(
                unresolvedBattleMapEntity.account.id.eq(accountId),
                unresolvedBattleMapEntity.categoryId.eq(categoryId),
            )
        if (visibleOnly) query.where(unresolvedBattleMapEntity.visible.isTrue)

        return query
            .orderBy(
                unresolvedBattleMapEntity.groupDisplayOrder.asc(),
                unresolvedBattleMapEntity.mapDisplayOrder.asc(),
                unresolvedBattleMapEntity.observedName.asc(),
                unresolvedBattleMapEntity.id.asc(),
            )
            .fetch()
    }
}
