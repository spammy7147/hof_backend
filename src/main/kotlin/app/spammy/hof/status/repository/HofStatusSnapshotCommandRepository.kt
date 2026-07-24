package app.spammy.hof.status.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.status.entity.HofStatusSnapshotEntity

/** 최신 HOF 상태 스냅샷의 쓰기 기능만 노출한다. */
interface HofStatusSnapshotCommandRepository : CommandRepository<HofStatusSnapshotEntity, Long>
