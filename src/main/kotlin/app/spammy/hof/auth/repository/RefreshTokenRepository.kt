package app.spammy.hof.auth.repository

import app.spammy.hof.auth.entity.RefreshTokenEntity
import app.spammy.hof.common.persistence.CommandRepository

/** refresh token row의 저장·삭제·flush만 담당하며, 모든 조회는 [RefreshTokenQueryRepository]로 제한한다. */
interface RefreshTokenRepository : CommandRepository<RefreshTokenEntity, Long>
