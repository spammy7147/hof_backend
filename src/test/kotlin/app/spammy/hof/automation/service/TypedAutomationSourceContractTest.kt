package app.spammy.hof.automation.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class TypedAutomationSourceContractTest {
    @Test
    fun `unified runner has no legacy fallback dependencies`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/app/spammy/hof/automation/service/UnifiedAutomationRunner.kt"),
        )

        listOf(
            "AutomationCheckpointService",
            "AutomationSnapshotLoader",
            "AutomationDecisionPolicy",
            "AutomationActionExecutor",
            "TypedAutomationEngineSelector",
        ).forEach { legacyType ->
            assertFalse(
                Regex("""\b${Regex.escape(legacyType)}\b""").containsMatchIn(source),
                "$legacyType must not remain in the typed-only runner",
            )
        }
    }

    @Test
    fun `unified service has only typed automation dependencies`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/app/spammy/hof/automation/service/UnifiedAutomationService.kt"),
        )

        listOf(
            "AutomationModuleConfigRepository",
            "AutomationProfileRepository",
            "AutomationJobRepository",
            "UnifiedAutomationQueryRepository",
            "AutomationAfterCommitWakeupService",
            "AutomationModuleReadinessEvaluator",
        ).forEach { legacyType ->
            assertFalse(
                Regex("""\b${Regex.escape(legacyType)}\b""").containsMatchIn(source),
                "$legacyType must not remain in the typed-only service",
            )
        }
    }
}
