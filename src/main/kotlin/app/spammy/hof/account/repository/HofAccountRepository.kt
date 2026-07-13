package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * HOF 계정 엔티티의 쓰기 작업만 담당하는 Spring Data repository다.
 */
interface HofAccountRepository : CommandRepository<HofAccountEntity, Long>
