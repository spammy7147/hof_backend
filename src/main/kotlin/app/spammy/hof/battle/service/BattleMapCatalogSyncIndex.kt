package app.spammy.hof.battle.service

import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.entity.UnresolvedBattleMapEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.external.model.HofBattleMap

/** 한 계정/카테고리 동기화 트랜잭션에서 미해결 관측을 찾는 정규화 정체성이다. */
internal data class UnresolvedBattleMapIdentity(
    val categoryId: String,
    val groupNormalizedName: String,
    val normalizedName: String,
) {
    companion object {
        /** HOF 관측값을 category·group·map 이름의 안정적인 미해결 key로 변환한다. */
        fun from(observation: HofBattleMap): UnresolvedBattleMapIdentity =
            UnresolvedBattleMapIdentity(
                categoryId = observation.categoryId,
                groupNormalizedName = BattleMapIdentityNormalizer.normalize(observation.groupName),
                normalizedName = BattleMapIdentityNormalizer.normalize(observation.name),
            )

        /** 기존 unresolved row를 같은 인메모리 key로 복원한다. */
        fun from(row: UnresolvedBattleMapEntity): UnresolvedBattleMapIdentity =
            UnresolvedBattleMapIdentity(
                categoryId = row.categoryId,
                groupNormalizedName = row.groupNormalizedName,
                normalizedName = row.normalizedName,
            )
    }
}

/**
 * 동기화 시작 시 QueryDSL로 한 번씩 읽은 카탈로그와 계정 상태의 가변 인메모리 인덱스다.
 * 새로 저장한 맵·그룹·별칭·상태·미해결 행도 즉시 반영해 뒤 관측이 DB를 다시 읽지 않게 한다.
 */
internal class BattleMapCatalogSyncIndex(
    maps: Collection<BattleMapEntity>,
    groups: Collection<BattleMapGroupEntity>,
    aliases: Collection<BattleMapAliasEntity>,
    states: Collection<AccountBattleMapStateEntity>,
    unresolvedRows: Collection<UnresolvedBattleMapEntity>,
) {
    private val mapsByCode = maps.associateByTo(linkedMapOf(), BattleMapEntity::mapCode)
    private val groupsByNormalizedName = linkedMapOf<String, BattleMapGroupEntity>()
    private val aliases = mutableListOf<BattleMapAliasEntity>()
    private val aliasesByMapId = linkedMapOf<Long, MutableList<BattleMapAliasEntity>>()
    private val statesByMapId = states.associateByTo(linkedMapOf()) { state -> state.battleMap.id }
    private val unresolvedByIdentity = unresolvedRows.associateByTo(linkedMapOf(), UnresolvedBattleMapIdentity::from)

    init {
        groups.forEach(::putGroup)
        putAliases(aliases)
    }

    /** 직접 mapCode를 우선하고, 코드가 없는 placeholder만 현재 alias index에서 해결한다. */
    fun resolve(observation: HofBattleMap): BattleMapEntity? {
        observation.mapCode
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { mapCode -> return mapsByCode[mapCode] }
        return resolveBattleMapAlias(observation, aliases)
    }

    /** 같은 동기화에서 새로 학습한 mapCode를 뒤 관측이 즉시 사용할 수 있게 등록한다. */
    fun putMap(map: BattleMapEntity) {
        mapsByCode[map.mapCode] = map
    }

    /** 정규화한 HOF 그룹 이름으로 현재 catalog group을 찾는다. */
    fun findGroup(normalizedName: String): BattleMapGroupEntity? =
        groupsByNormalizedName[normalizedName]

    /** 신규·갱신 group을 ID와 이름 중복 없이 index에 반영한다. */
    fun putGroup(group: BattleMapGroupEntity) {
        groupsByNormalizedName.entries.removeIf { (_, existing) ->
            existing === group || (group.id != 0L && existing.id == group.id)
        }
        groupsByNormalizedName[BattleMapIdentityNormalizer.normalize(group.name)] = group
    }

    /** 한 맵에 이미 등록된 alias snapshot을 반환한다. */
    fun aliasesForMap(mapId: Long): List<BattleMapAliasEntity> =
        aliasesByMapId[mapId].orEmpty()

    /** 새 alias를 맵별·전체 검색 index 양쪽에 중복 없이 반영한다. */
    fun putAliases(newAliases: Collection<BattleMapAliasEntity>) {
        newAliases.forEach { alias ->
            val mapAliases = aliasesByMapId.getOrPut(alias.battleMap.id) { mutableListOf() }
            if (mapAliases.none { existing -> existing.normalizedAlias == alias.normalizedAlias }) {
                mapAliases += alias
                aliases += alias
            }
        }
    }

    /** 계정의 기존 동적 상태 row를 static map ID로 찾는다. */
    fun stateFor(mapId: Long): AccountBattleMapStateEntity? = statesByMapId[mapId]

    /** 새로 만든 상태를 뒤 관측과 최종 hide 처리에서 재사용할 수 있게 등록한다. */
    fun putState(state: AccountBattleMapStateEntity) {
        statesByMapId[state.battleMap.id] = state
    }

    /** 이번 동기화가 관리하는 전체 계정 상태 snapshot을 반환한다. */
    fun states(): List<AccountBattleMapStateEntity> = statesByMapId.values.toList()

    /** 같은 category·group·name으로 앞서 저장한 unresolved row를 찾는다. */
    fun unresolvedFor(identity: UnresolvedBattleMapIdentity): UnresolvedBattleMapEntity? =
        unresolvedByIdentity[identity]

    /** 신규 unresolved 관측을 현재 transaction의 index에 등록한다. */
    fun putUnresolved(row: UnresolvedBattleMapEntity) {
        unresolvedByIdentity[UnresolvedBattleMapIdentity.from(row)] = row
    }

    /** 정상 map으로 해결된 identity를 unresolved index에서 제거하고 삭제 대상을 반환한다. */
    fun removeUnresolved(identity: UnresolvedBattleMapIdentity): UnresolvedBattleMapEntity? =
        unresolvedByIdentity.remove(identity)

    /** 이번 동기화 이후 남아 있는 unresolved row 전체를 반환한다. */
    fun unresolvedRows(): List<UnresolvedBattleMapEntity> = unresolvedByIdentity.values.toList()
}

/**
 * 이미 적재한 별칭만으로 placeholder 관측을 해결한다.
 * 관측 그룹이 있으면 같은 정규화 그룹 후보만 허용하고, 그룹이 없을 때만 카테고리 전체의 유일성을 사용한다.
 */
internal fun resolveBattleMapAlias(
    observation: HofBattleMap,
    aliases: Collection<BattleMapAliasEntity>,
): BattleMapEntity? {
    val observedAliases = BattleMapIdentityNormalizer.aliases(observation.name)
    if (observedAliases.isEmpty()) return null

    val exactAliases = aliases.filter { alias -> alias.normalizedAlias in observedAliases }
    chooseUniqueMap(exactAliases, observation.groupName)?.let { return it }

    val suffixAliases = aliases.filter { candidate ->
        val candidateAlias = candidate.normalizedAlias
        candidateAlias.isNotBlank() && observedAliases.any { observedAlias ->
            observedAlias.endsWith(candidateAlias) || candidateAlias.endsWith(observedAlias)
        }
    }
    return chooseUniqueMap(suffixAliases, observation.groupName)
}

private fun chooseUniqueMap(
    aliases: Collection<BattleMapAliasEntity>,
    observedGroupName: String?,
): BattleMapEntity? {
    val candidates = aliases
        .map(BattleMapAliasEntity::battleMap)
        .distinctBy(BattleMapEntity::id)
    if (observedGroupName != null) {
        val normalizedGroupName = BattleMapIdentityNormalizer.normalize(observedGroupName)
        return candidates
            .filter { candidate -> BattleMapIdentityNormalizer.normalize(candidate.group?.name) == normalizedGroupName }
            .singleOrNull()
    }
    return candidates.singleOrNull()
}
