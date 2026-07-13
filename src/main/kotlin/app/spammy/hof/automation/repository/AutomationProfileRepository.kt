package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.AutomationProfileMapEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * 자동전투 프로필 부모 행의 저장·삭제·flush만 노출하는 command repository다.
 */
interface AutomationProfileRepository : CommandRepository<AutomationProfileEntity, Long>

/**
 * 프로필 맵 행의 일괄 저장·삭제·flush만 노출한다.
 *
 * 수정 시 기존 행을 삭제하고 flush한 뒤 같은 맵 FK를 다시 넣어 유일 키 충돌을 피한다.
 */
interface AutomationProfileMapCommandRepository : CommandRepository<AutomationProfileMapEntity, Long>
