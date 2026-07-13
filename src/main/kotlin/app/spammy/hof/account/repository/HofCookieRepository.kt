package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * HOF 원본 서버 로그인 세션 쿠키의 쓰기 작업만 담당하는 Spring Data repository다.
 */
interface HofCookieRepository : CommandRepository<HofCookieEntity, Long>
