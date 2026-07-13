package app.spammy.hof.captcha.repository

import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * 캡차 challenge row의 저장·삭제·flush만 노출하는 command repository다.
 *
 * 현재 challenge, 소유권, 상태를 포함한 모든 조회는 [CaptchaQueryRepository]가 담당한다.
 */
interface CaptchaChallengeRepository : CommandRepository<CaptchaChallengeEntity, Long>
