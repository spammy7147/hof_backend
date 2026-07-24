package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.entity.UnresolvedBattleMapEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.model.hasUsableKey
import app.spammy.hof.battle.repository.AccountBattleMapStateCommandRepository
import app.spammy.hof.battle.repository.BattleMapAliasCommandRepository
import app.spammy.hof.battle.repository.BattleMapGroupCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.battle.repository.UnresolvedBattleMapCommandRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofBattleMap
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 한 로컬 JVM에서 전투 맵 카탈로그 동기화를 직렬화하는 facade다.
 *
 * 이 애플리케이션은 파일 DB를 쓰는 단일 Spring 프로세스로 운영되므로 네트워크 락 대신 JVM 락을 쓴다.
 * 락 안에서 별도의 transactional bean을 호출해 DB commit이 완료될 때까지 다음 새로고침을 대기시킨다.
 */
@Service
class BattleMapCatalogService(
    private val transactionService: BattleMapCatalogTransactionService,
) {
    /**
     * 한 번 파싱한 카테고리 관측 전체를 JVM 공정 락 안에서 DB 상태로 반영한다.
     * commit이 끝난 뒤 보이는 맵 목록을 반환하므로 같은 프로세스의 동시 새로고침이 중간 상태를 읽지 않는다.
     */
    fun synchronizeCategory(
        account: HofAccountEntity,
        categoryId: String,
        observations: List<HofBattleMap>,
    ): List<HofBattleMap> =
        withSynchronizationFence {
            transactionService.synchronizeCategory(account, categoryId, observations)
        }

    /** 외부 HOF 호출 없이 마지막으로 저장된 계정별 visible 맵 tree를 조회한다. */
    fun findVisibleByCategory(
        accountId: Long,
        categoryId: String,
    ): List<HofBattleMap> =
        transactionService.findVisibleByCategory(accountId, categoryId)

    companion object {
        private val SYNCHRONIZATION_LOCK = ReentrantLock(true)

        /** One global order: acquire this reentrant JVM fence before beginning any catalog-writing DB transaction. */
        fun <T> withSynchronizationFence(block: () -> T): T = SYNCHRONIZATION_LOCK.withLock(block)
    }
}

/**
 * 공유 맵 카탈로그와 계정별 관측 상태를 한 DB 트랜잭션에서 동기화한다.
 * 계정 새로고침은 해당 계정의 기존 행만 숨기고, 정적 맵·그룹·별칭은 삭제하지 않는다.
 */
@Service
class BattleMapCatalogTransactionService(
    private val queryRepository: BattleMapQueryRepository,
    private val mapRepository: BattleMapRepository,
    private val groupRepository: BattleMapGroupCommandRepository,
    private val aliasRepository: BattleMapAliasCommandRepository,
    private val stateRepository: AccountBattleMapStateCommandRepository,
    private val unresolvedRepository: UnresolvedBattleMapCommandRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(BattleMapCatalogTransactionService::class.java)

    /**
     * 한 계정의 카테고리 관측 전체를 적용하고, 현재 보이는 해결/미해결 트리를 반환한다.
     * 동기화 시작에 카탈로그/계정 행을 고정된 다섯 번의 벌크 조회로 적재하고 관측별 추가 조회를 하지 않는다.
     */
    @Transactional
    fun synchronizeCategory(
        account: HofAccountEntity,
        categoryId: String,
        observations: List<HofBattleMap>,
    ): List<HofBattleMap> {
        val now = timeProvider.now()
        val index = preloadIndex(account.id, categoryId)
        if (observations.isEmpty()) {
            return assembleVisibleTree(
                states = index.states().filter(AccountBattleMapStateEntity::visible),
                unresolved = index.unresolvedRows().filter(UnresolvedBattleMapEntity::visible),
                now = now,
            )
        }
        hideExistingRows(index)

        observations.forEach { sourceObservation ->
            val observation = sourceObservation.copy(categoryId = categoryId)
            val resolvedMap = index.resolve(observation)
                ?: observation.mapCode
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { bootstrapDirectMap(index, observation, it, now) }

            if (resolvedMap == null) {
                upsertUnresolved(index, account, observation, now)
            } else {
                refreshStaticMetadata(index, resolvedMap, observation, now)
                ensureAliases(index, resolvedMap, observation.name)
                upsertState(index, account, resolvedMap, observation, now)
                removeMatchingUnresolved(index, observation)
            }
        }

        stateRepository.flush()
        unresolvedRepository.flush()
        val result = assembleVisibleTree(
            states = index.states().filter(AccountBattleMapStateEntity::visible),
            unresolved = index.unresolvedRows().filter(UnresolvedBattleMapEntity::visible),
            now = now,
        )
        log.info(
            "Battle map state synchronized accountId={} categoryId={} observationCount={} resultCount={}",
            account.id,
            categoryId,
            observations.size,
            result.size,
        )
        return result
    }

    /** 원본 호출 없이 DB에 현재 보이는 계정별 맵 트리를 조회한다. */
    @Transactional(readOnly = true)
    fun findVisibleByCategory(
        accountId: Long,
        categoryId: String,
    ): List<HofBattleMap> =
        loadVisibleTree(accountId, categoryId, timeProvider.now())

    private fun preloadIndex(
        accountId: Long,
        categoryId: String,
    ): BattleMapCatalogSyncIndex =
        BattleMapCatalogSyncIndex(
            maps = queryRepository.findMapsByCategoryId(categoryId),
            groups = queryRepository.findGroupsByCategoryId(categoryId),
            aliases = queryRepository.findAliasesByCategoryId(categoryId),
            states = queryRepository.findStatesByAccountIdAndCategoryId(accountId, categoryId),
            unresolvedRows = queryRepository.findUnresolvedByAccountIdAndCategoryId(accountId, categoryId),
        )

    private fun hideExistingRows(index: BattleMapCatalogSyncIndex) {
        index.states()
            .onEach { state -> state.visible = false }
            .takeIf(List<AccountBattleMapStateEntity>::isNotEmpty)
            ?.let(stateRepository::saveAll)
        index.unresolvedRows()
            .onEach { unresolved -> unresolved.visible = false }
            .takeIf(List<UnresolvedBattleMapEntity>::isNotEmpty)
            ?.let(unresolvedRepository::saveAll)
    }

    private fun bootstrapDirectMap(
        index: BattleMapCatalogSyncIndex,
        observation: HofBattleMap,
        mapCode: String,
        now: Instant,
    ): BattleMapEntity {
        val group = resolveGroup(index, observation)
        val map = mapRepository.save(
            BattleMapEntity(
                categoryId = observation.categoryId,
                mapCode = mapCode,
                group = group,
                name = observation.name,
                normalizedName = BattleMapIdentityNormalizer.normalize(observation.name),
                displayOrder = observation.mapOrder,
                requiredTime = observation.requiredTime,
                iconUrl = observation.iconUrl,
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        mapRepository.flush()
        index.putMap(map)
        return map
    }

    private fun refreshStaticMetadata(
        index: BattleMapCatalogSyncIndex,
        map: BattleMapEntity,
        observation: HofBattleMap,
        now: Instant,
    ) {
        val observedGroup = resolveGroup(index, observation)
        if (observedGroup != null) map.group = observedGroup
        map.name = observation.name
        map.normalizedName = BattleMapIdentityNormalizer.normalize(observation.name)
        map.displayOrder = observation.mapOrder
        observation.requiredTime?.let { map.requiredTime = it }
        observation.iconUrl?.let { map.iconUrl = it }
        map.updatedAt = now
        mapRepository.save(map)
    }

    private fun resolveGroup(
        index: BattleMapCatalogSyncIndex,
        observation: HofBattleMap,
    ): BattleMapGroupEntity? {
        val observedName = observation.groupName?.trim()?.takeIf(String::isNotBlank) ?: return null
        val normalizedName = BattleMapIdentityNormalizer.normalize(observedName)
        val group = index.findGroup(normalizedName)
            ?: groupRepository.save(
                BattleMapGroupEntity(
                    categoryId = observation.categoryId,
                    name = observedName,
                    displayOrder = observation.groupOrder,
                    recommendedLevel = observation.recommendedLevel,
                ),
            ).also { groupRepository.flush() }

        group.name = observedName
        group.displayOrder = observation.groupOrder
        observation.recommendedLevel?.let { group.recommendedLevel = it }
        return groupRepository.save(group).also(index::putGroup)
    }

    private fun ensureAliases(
        index: BattleMapCatalogSyncIndex,
        map: BattleMapEntity,
        observedName: String,
    ) {
        val existingAliases = index.aliasesForMap(map.id)
            .map(BattleMapAliasEntity::normalizedAlias)
            .toSet()
        val newAliases = BattleMapIdentityNormalizer.aliasValues(observedName)
            .map { alias -> alias to BattleMapIdentityNormalizer.normalize(alias) }
            .filterNot { (_, normalizedAlias) -> normalizedAlias in existingAliases }
            .map { (alias, normalizedAlias) ->
                BattleMapAliasEntity(
                    battleMap = map,
                    alias = alias,
                    normalizedAlias = normalizedAlias,
                )
            }
        if (newAliases.isNotEmpty()) {
            val savedAliases = aliasRepository.saveAll(newAliases)
            aliasRepository.flush()
            index.putAliases(savedAliases)
        }
    }

    private fun upsertState(
        index: BattleMapCatalogSyncIndex,
        account: HofAccountEntity,
        map: BattleMapEntity,
        observation: HofBattleMap,
        now: Instant,
    ) {
        val state = index.stateFor(map.id)
            ?: AccountBattleMapStateEntity(
                account = account,
                battleMap = map,
                rawHref = observation.rawHref,
                lastSeenAt = now,
            )
        state.keyMode = observation.keyMode
        state.keyCount = observation.keyCount
        state.availableCount = observation.availableCount
        state.attemptRemaining = observation.attemptCount
        state.winRemaining = observation.winCount
        val observedCooldown = observation.cooldownRemainingSeconds?.let(now::plusSeconds)
        state.cooldownUntil = if (map.sharesMinuteCooldown && state.cooldownUntil?.isAfter(now) == true) {
            listOfNotNull(state.cooldownUntil, observedCooldown).maxOrNull()
        } else {
            observedCooldown
        }
        observation.supportsThreeBattles?.let { state.supportsThreeBattles = it }
        state.rawHref = observation.rawHref
        state.visible = true
        state.lastSeenAt = now
        stateRepository.save(state)
        index.putState(state)
    }

    private fun upsertUnresolved(
        index: BattleMapCatalogSyncIndex,
        account: HofAccountEntity,
        observation: HofBattleMap,
        now: Instant,
    ) {
        val identity = UnresolvedBattleMapIdentity.from(observation)
        val unresolved = index.unresolvedFor(identity) ?: UnresolvedBattleMapEntity(
            account = account,
            categoryId = observation.categoryId,
            groupNormalizedName = identity.groupNormalizedName,
            normalizedName = identity.normalizedName,
            observedName = observation.name,
            rawHref = observation.rawHref,
            lastSeenAt = now,
        )
        unresolved.groupName = observation.groupName
        unresolved.groupDisplayOrder = observation.groupOrder
        unresolved.mapDisplayOrder = observation.mapOrder
        unresolved.observedName = observation.name
        unresolved.recommendedLevel = observation.recommendedLevel
        unresolved.keyMode = observation.keyMode
        unresolved.keyCount = observation.keyCount
        unresolved.availableCount = observation.availableCount
        unresolved.attemptRemaining = observation.attemptCount
        unresolved.winRemaining = observation.winCount
        unresolved.cooldownUntil = observation.cooldownRemainingSeconds?.let(now::plusSeconds)
        unresolved.requiredTime = observation.requiredTime
        unresolved.iconUrl = observation.iconUrl
        unresolved.rawHref = observation.rawHref
        unresolved.visible = true
        unresolved.lastSeenAt = now
        unresolvedRepository.save(unresolved)
        index.putUnresolved(unresolved)
    }

    private fun removeMatchingUnresolved(
        index: BattleMapCatalogSyncIndex,
        observation: HofBattleMap,
    ) {
        index.removeUnresolved(UnresolvedBattleMapIdentity.from(observation))
            ?.let(unresolvedRepository::delete)
    }

    private fun loadVisibleTree(
        accountId: Long,
        categoryId: String,
        now: Instant,
    ): List<HofBattleMap> {
        val states = queryRepository.findVisibleStatesByAccountIdAndCategoryId(accountId, categoryId)
        val unresolved = queryRepository.findVisibleUnresolvedByAccountIdAndCategoryId(accountId, categoryId)
        return assembleVisibleTree(states, unresolved, now)
    }

    private fun assembleVisibleTree(
        states: List<AccountBattleMapStateEntity>,
        unresolved: List<UnresolvedBattleMapEntity>,
        now: Instant,
    ): List<HofBattleMap> {
        val resolvedMaps = states
            .map { state -> state.toDomain(now) }
        val unresolvedMaps = unresolved
            .map { row -> row.toDomain(now) }
        return (resolvedMaps + unresolvedMaps).sortedWith(
            compareBy<HofBattleMap> { map -> map.groupOrder }
                .thenBy { map -> map.groupName.orEmpty() }
                .thenBy { map -> map.mapOrder }
                .thenBy { map -> map.name }
                .thenBy { map -> map.mapCode.orEmpty() },
        )
    }

    private fun AccountBattleMapStateEntity.toDomain(now: Instant): HofBattleMap {
        val activeCooldown = cooldownUntil?.isAfter(now) == true
        val hasZeroDynamicLimit = listOf(availableCount, attemptRemaining, winRemaining)
            .any { count -> count != null && count <= 0 }
        return HofBattleMap(
            categoryId = battleMap.categoryId,
            mapCode = battleMap.mapCode,
            name = battleMap.name,
            groupName = battleMap.group?.name,
            groupOrder = battleMap.group?.displayOrder ?: 0,
            mapOrder = battleMap.displayOrder,
            recommendedLevel = battleMap.group?.recommendedLevel,
            availableCount = availableCount,
            attemptCount = attemptRemaining,
            winCount = winRemaining,
            cooldownRemainingSeconds = remainingSeconds(cooldownUntil, now),
            keyMode = keyMode,
            keyCount = keyCount,
            requiredTime = battleMap.requiredTime,
            supportsThreeBattles = supportsThreeBattles,
            enabled = battleMap.enabled && visible && keyMode.hasUsableKey(keyCount) && !hasZeroDynamicLimit && !activeCooldown,
            resolved = true,
            iconUrl = battleMap.iconUrl,
            rawHref = rawHref,
        )
    }

    private fun UnresolvedBattleMapEntity.toDomain(now: Instant): HofBattleMap =
        HofBattleMap(
            categoryId = categoryId,
            mapCode = null,
            name = observedName,
            groupName = groupName,
            groupOrder = groupDisplayOrder,
            mapOrder = mapDisplayOrder,
            recommendedLevel = recommendedLevel,
            availableCount = availableCount,
            attemptCount = attemptRemaining,
            winCount = winRemaining,
            cooldownRemainingSeconds = remainingSeconds(cooldownUntil, now),
            keyMode = keyMode,
            keyCount = keyCount,
            requiredTime = requiredTime,
            enabled = false,
            resolved = false,
            iconUrl = iconUrl,
            rawHref = rawHref,
        )

    private fun remainingSeconds(
        until: Instant?,
        now: Instant,
    ): Long? {
        if (until == null || !until.isAfter(now)) return null
        return Duration.between(now, until).seconds.coerceAtLeast(1L)
    }
}
