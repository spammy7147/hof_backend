package app.spammy.hof.captcha.repository

import app.spammy.hof.captcha.entity.CaptchaFormFieldEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * 캡차 form field row의 저장, 교체 삭제, flush만 노출하는 command repository다.
 *
 * 기존 row 조회는 [CaptchaQueryRepository]가 담당한다. 같은 challenge의 field 이름을 교체할 때는
 * 조회한 row를 삭제한 뒤 flush하고 새 row를 넣어 unique 제약과 Hibernate 실행 순서가 충돌하지 않게 한다.
 */
interface CaptchaFormFieldCommandRepository : CommandRepository<CaptchaFormFieldEntity, Long>
