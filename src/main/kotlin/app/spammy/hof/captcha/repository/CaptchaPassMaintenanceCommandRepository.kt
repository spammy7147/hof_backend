package app.spammy.hof.captcha.repository

import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import app.spammy.hof.common.persistence.CommandRepository

/** 통행증 자동 갱신 상태의 쓰기 기능만 노출한다. */
interface CaptchaPassMaintenanceCommandRepository : CommandRepository<CaptchaPassMaintenanceEntity, Long>
