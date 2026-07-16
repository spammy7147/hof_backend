package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class UnifiedAutomationRunnerTest {
    private val preflight = Mockito.mock(AutomationDailyPreflight::class.java)
    private val runtime = Mockito.mock(TypedAutomationRuntimeService::class.java)
    private val loader = Mockito.mock(TypedAutomationSnapshotLoader::class.java)
    private val coordinator = Mockito.mock(AutomationCoordinator::class.java)
    private val executor = Mockito.mock(TypedAutomationActionExecutor::class.java)
    private val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
    private val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
    private val runner = UnifiedAutomationRunner(preflight, runtime, loader, coordinator, executor, codec, wakeup)

    init {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(true)
    }

    @Test
    fun `runner persists submits and checkpoints one action then wakes a fresh evaluation`() {
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = BattleMapAutomationAction(
            7,
            LocalDate.parse("2026-07-16"),
            "battle_map",
            "gb0",
            PresetSelectionMode.PRIMARY,
            301,
            1,
            "execution-1",
            resolvedParty = ResolvedAutomationParty(
                listOf("character-1"),
                listOf(BattlePatternLoadRequest("character-1", 1)),
            ),
        )
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, emptyList()))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "token", 88L)).thenReturn(true)

        runner.runOne(7)

        Mockito.verify(executor).execute(Mockito.eq(7L), anyStoredAction())
        Mockito.verify(runtime).recordWarnings(7, "token", emptyList())
        Mockito.verify(runtime).succeedAndEnqueueWake(7, "token", 88L, "TYPED_ACTION_COMPLETED")
    }

    @Test
    fun `prepare and submitting races explicitly release each claimed token`() {
        val snapshot = AutomationCoordinatorSnapshot(emptyList())
        val action = QuestAction.Claim("quest", "claim")
        val row = Mockito.mock(TypedAutomationActionRunEntity::class.java)
        Mockito.`when`(row.id).thenReturn(88L)
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(
            TypedRuntimeClaim.Acquired("prepare-token"),
            TypedRuntimeClaim.Acquired("submit-token"),
        )
        Mockito.`when`(loader.loadTyped(7)).thenReturn(snapshot)
        Mockito.`when`(coordinator.coordinate(snapshot)).thenReturn(AutomationCoordination.Runnable(12, action, listOf("warning")))
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("prepare-token"), anyStoredAction())).thenReturn(null)
        Mockito.`when`(runtime.prepare(Mockito.eq(7L), eqString("submit-token"), anyStoredAction())).thenReturn(row)
        Mockito.`when`(runtime.markSubmitting(7, "submit-token", 88)).thenReturn(false)

        runner.runOne(7)
        runner.runOne(7)

        Mockito.verify(runtime).releaseAndEnqueueWake(7, "prepare-token", "TYPED_CONFIG_RELOAD")
        Mockito.verify(runtime).releaseAndEnqueueWake(7, "submit-token", "TYPED_CONFIG_RELOAD")
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    fun `configuration change releases lease and requests immediate durable reload`() {
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenThrow(TypedAutomationConfigurationChangedException())

        runner.runOne(7)

        Mockito.verify(runtime).releaseAndEnqueueWake(7, "token", "TYPED_CONFIG_RELOAD")
        Mockito.verifyNoInteractions(wakeup, executor)
    }

    @Test
    fun `inactive typed runtime returns before preflight or any HOF work`() {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(false)

        runner.runOne(7)

        Mockito.verify(runtime).isRunning(7)
        Mockito.verifyNoInteractions(preflight, loader, coordinator, executor, wakeup)
        Mockito.verify(runtime, Mockito.never()).claim(7)
    }

    @Test
    fun `preflight terminal stop transitions once and its wake cannot call preflight again`() {
        Mockito.`when`(runtime.isRunning(7)).thenReturn(true, false)
        Mockito.`when`(preflight.ensureReady(7))
            .thenReturn(AutomationDailyPreflight.Result.Stopped(AutomationDailyPreflight.StopReason.NETWORK))

        runner.runOne(7)
        runner.runOne(7)

        Mockito.verify(preflight, Mockito.times(1)).ensureReady(7)
        Mockito.verify(runtime, Mockito.times(1)).stop(7, AutomationStopReason.NETWORK)
        Mockito.verify(runtime, Mockito.never()).claim(7)
        Mockito.verifyNoInteractions(loader, coordinator, executor, wakeup)
    }

    @Test
    fun `session login failure stops typed runtime for authentication`() {
        preparedActionFailure(
            IllegalStateException("wrapped login failure", AutomationLoginRequiredException()),
            AutomationStopReason.AUTHENTICATION,
        )
    }

    @Test
    fun `captcha response stops typed runtime for captcha instead of network`() {
        preparedActionFailure(
            IllegalStateException(
                "wrapped captcha",
                ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"),
            ),
            AutomationStopReason.CAPTCHA,
        )
    }

    @Test
    fun `captcha live snapshot stops typed runtime before preparing any action`() {
        liveSnapshotFailure(
            ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"),
            AutomationStopReason.CAPTCHA,
        )
    }

    @Test
    fun `failed live snapshot login recovery stops typed runtime for authentication`() {
        liveSnapshotFailure(
            AutomationLoginRequiredException(),
            AutomationStopReason.AUTHENTICATION,
        )
    }

    @Test
    fun `ambiguous submission stops typed runtime for network without replay`() {
        preparedActionFailure(
            IllegalStateException(
                "wrapped ambiguous outcome",
                AmbiguousAutomationSubmissionException("unknown outcome"),
            ),
            AutomationStopReason.NETWORK,
        )
    }

    @Test
    fun `tampered stored envelopes stop fatally before submitting or posting`() {
        val stored = StoredTypedAutomationActionV1(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestClaim("quest", "claim"),
        )
        val encoded = codec.encode(stored)
        data class Corruption(
            val name: String,
            val accountId: Long = 7,
            val entryId: Long = 12,
            val execution: String = "execution-1",
            val kind: String = "QUEST_CLAIM",
            val schema: Int = 1,
            val json: String = encoded.json,
            val fingerprint: String = encoded.fingerprint,
        )
        val changedPayload = codec.encode(
            stored.copy(payload = StoredTypedActionPayload.QuestClaim("tampered", "claim")),
        ).json
        val cases = listOf(
            Corruption("payload", json = changedPayload),
            Corruption("whitespace-bytes", json = "  ${encoded.json}\n"),
            Corruption(
                "reordered-bytes",
                json = "{\"executionIdentity\":\"execution-1\",\"entryId\":12,\"payload\":{\"kind\":\"QUEST_CLAIM\",\"questCode\":\"quest\",\"actionNo\":\"claim\"}}",
            ),
            Corruption("fingerprint", fingerprint = "0".repeat(64)),
            Corruption("kind", kind = "QUEST_ACCEPT"),
            Corruption("account", accountId = 8),
            Corruption("entry", entryId = 13),
            Corruption("execution", execution = "different"),
            Corruption("schema", schema = 99),
        )
        cases.forEach { corruption ->
            val casePreflight = Mockito.mock(AutomationDailyPreflight::class.java)
            val caseRuntime = Mockito.mock(TypedAutomationRuntimeService::class.java)
            val caseExecutor = Mockito.mock(TypedAutomationActionExecutor::class.java)
            val owner = HofAccountEntity(corruption.accountId, "login-${corruption.name}", "encrypted", Instant.EPOCH)
            val entry = AutomationEntryEntity(
                corruption.entryId,
                owner,
                AutomationType.QUEST,
                0,
                true,
                Instant.EPOCH,
                Instant.EPOCH,
            )
            val row = TypedAutomationActionRunEntity(
                88,
                owner,
                entry,
                corruption.execution,
                corruption.kind,
                corruption.schema,
                corruption.json,
                corruption.fingerprint,
                TypedAutomationActionStatus.PREPARED,
                leaseToken = "token",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
            val caseRunner = UnifiedAutomationRunner(
                casePreflight,
                caseRuntime,
                Mockito.mock(TypedAutomationSnapshotLoader::class.java),
                Mockito.mock(AutomationCoordinator::class.java),
                caseExecutor,
                codec,
                wakeup,
            )
            Mockito.`when`(casePreflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
            Mockito.`when`(caseRuntime.isRunning(7)).thenReturn(true)
            Mockito.`when`(caseRuntime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))

            caseRunner.runOne(7)

            Mockito.verify(caseRuntime).stop(
                7,
                "token",
                88,
                AutomationStopReason.FATAL,
                "Stored typed action integrity check failed.",
            )
            Mockito.verifyNoInteractions(caseExecutor)
        }
    }

    private fun anyStoredAction(): StoredTypedAutomationActionV1 =
        Mockito.any(StoredTypedAutomationActionV1::class.java)
            ?: StoredTypedAutomationActionV1(1, "any", StoredTypedActionPayload.QuestClaim("q", "a"))

    private fun eqString(value: String): String = Mockito.eq(value) ?: value

    private fun preparedActionFailure(error: Throwable, expectedReason: AutomationStopReason) {
        val stored = StoredTypedAutomationActionV1(
            12,
            "execution-1",
            StoredTypedActionPayload.QuestClaim("quest", "claim"),
        )
        val encoded = codec.encode(stored)
        val owner = HofAccountEntity(7, "login", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, owner, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
        val row = TypedAutomationActionRunEntity(
            88,
            owner,
            entry,
            stored.executionIdentity,
            stored.payload.kind(),
            1,
            encoded.json,
            encoded.fingerprint,
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token", row))
        Mockito.`when`(runtime.markSubmitting(7, "token", 88)).thenReturn(true)
        Mockito.doThrow(error).`when`(executor).execute(Mockito.eq(7L), anyStoredAction())

        runner.runOne(7)

        Mockito.verify(runtime).stop(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.eq(88L),
            eqValue(expectedReason),
            anyStringValue(),
        )
    }

    private fun <T> eqValue(value: T): T = Mockito.eq(value) ?: value
    private fun anyStringValue(): String = Mockito.anyString() ?: ""

    private fun liveSnapshotFailure(error: Throwable, expectedReason: AutomationStopReason) {
        Mockito.`when`(preflight.ensureReady(7)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.`when`(runtime.claim(7)).thenReturn(TypedRuntimeClaim.Acquired("token"))
        Mockito.`when`(loader.loadTyped(7)).thenThrow(error)

        runner.runOne(7)

        Mockito.verify(runtime).stop(
            Mockito.eq(7L),
            eqString("token"),
            Mockito.isNull(),
            eqValue(expectedReason),
            anyStringValue(),
        )
        Mockito.verifyNoInteractions(coordinator, executor)
    }
}
