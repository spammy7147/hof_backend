package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * 자동화 job 행의 저장·삭제·flush만 노출하는 command repository다.
 *
 * PK와 현재 job 조회는 [AutomationJobQueryRepository]가 QueryDSL로 전담한다.
 */
interface AutomationJobRepository : CommandRepository<AutomationJobEntity, Long>
