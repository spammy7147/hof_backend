package app.spammy.hof.external.client

import app.spammy.hof.character.service.CharacterRosterObservationService
import app.spammy.hof.character.service.SessionPatternLoadTracker
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.status.service.HofStatusSnapshotService
import org.mockito.Mockito

/** 기존 raw gateway fixture를 상태 관측 경계로 감싸는 단위 테스트 helper다. */
fun testAccountHofGateway(
    gateway: HofGateway,
    timeProvider: TimeProvider,
    patternLoads: SessionPatternLoadTracker = SessionPatternLoadTracker(),
): AccountHofGateway = AccountHofGateway(
    gateway = gateway,
    observations = AccountHofResponseObserver(
        snapshots = Mockito.mock(HofStatusSnapshotService::class.java),
        characterRosters = Mockito.mock(CharacterRosterObservationService::class.java),
        passMaintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java),
    ),
    timeProvider = timeProvider,
    patternLoads = patternLoads,
)
