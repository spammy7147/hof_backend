package app.spammy.hof.external.client

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.status.service.HofStatusSnapshotService
import org.mockito.Mockito

/** 기존 raw gateway fixture를 상태 관측 경계로 감싸는 단위 테스트 helper다. */
fun testAccountHofGateway(
    gateway: HofGateway,
    timeProvider: TimeProvider,
): AccountHofGateway = AccountHofGateway(
    gateway = gateway,
    snapshots = Mockito.mock(HofStatusSnapshotService::class.java),
    timeProvider = timeProvider,
)
