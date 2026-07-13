package app.spammy.hof.battle.repository

import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.entity.UnresolvedBattleMapEntity
import app.spammy.hof.common.persistence.CommandRepository

/** 정적 전투 맵 카탈로그의 쓰기 작업만 담당한다. */
interface BattleMapRepository : CommandRepository<BattleMapEntity, Long>

/** 전투 맵 그룹의 쓰기 작업만 담당한다. */
interface BattleMapGroupCommandRepository : CommandRepository<BattleMapGroupEntity, Long>

/** 전투 맵 별칭의 쓰기 작업만 담당한다. */
interface BattleMapAliasCommandRepository : CommandRepository<BattleMapAliasEntity, Long>

/** 계정별 전투 맵 상태의 쓰기 작업만 담당한다. */
interface AccountBattleMapStateCommandRepository : CommandRepository<AccountBattleMapStateEntity, Long>

/** 미해결 전투 맵 관측의 쓰기 작업만 담당한다. */
interface UnresolvedBattleMapCommandRepository : CommandRepository<UnresolvedBattleMapEntity, Long>
