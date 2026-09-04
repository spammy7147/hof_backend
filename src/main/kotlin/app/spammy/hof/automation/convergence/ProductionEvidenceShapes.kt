package app.spammy.hof.automation.convergence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** 원문 payload 대신 정책이 이해하는 정규화 response/form 구조만 식별한다. */
object ProductionEvidenceShapes {
    const val QUEST_RESPONSE =
        "QuestResponse|targetPresent|state|actionNoPresent|progressPresent"
    const val HOME_RESPONSE =
        "HomeResponse|targetPresent|state|actionIdPresent|resultStatus"
    const val BATTLE_RESPONSE =
        "BattleResultResponse|roundCount|terminalOutcomes"
    const val FISHING_RESPONSE =
        "FishingResponse|primaryAction|remainingCastsPresent|lastOutcome|blockedByBattle|resultStatus"
    const val RAID_RESPONSE =
        "RaidPubResponse|targetPresent|joined|status|battleTargetPresent|rewardAvailable|resultStatus|rewardResult"
    const val LOCAL_RAID_ABORT = "LocalRaidCycleAbort|reason|outcome"

    fun fingerprint(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    fun executionDiagnostic(actionKind: AutomationActionKind, resultKind: String): String =
        "source=execution;action=${actionKind.name};result=$resultKind"

    fun reconciliationDiagnostic(actionKind: AutomationActionKind, resultKind: String): String =
        "source=reconciliation;action=${actionKind.name};result=$resultKind"

    fun knownFingerprints(actionKind: AutomationActionKind): Set<String> = buildSet {
        add(fingerprint(responseShape(actionKind)))
        listOf("Completed", "BattleCompleted", "SharedCooldown", "RaidCycleFinished").forEach { result ->
            add(fingerprint(executionDiagnostic(actionKind, result)))
        }
        listOf("Applied", "Resubmit", "VerifyLater", "Held", "HandedOff", "Superseded").forEach { result ->
            add(fingerprint(reconciliationDiagnostic(actionKind, result)))
        }
    }

    private fun responseShape(actionKind: AutomationActionKind): String = when (actionKind) {
        AutomationActionKind.QUEST_ACCEPT,
        AutomationActionKind.QUEST_CLAIM,
        -> QUEST_RESPONSE
        AutomationActionKind.HOME_ACCEPT,
        AutomationActionKind.HOME_CLAIM,
        -> HOME_RESPONSE
        AutomationActionKind.FISHING_START,
        AutomationActionKind.FISHING_CATCH,
        -> FISHING_RESPONSE
        AutomationActionKind.RAID_RESET,
        AutomationActionKind.RAID_REGISTER,
        AutomationActionKind.RAID_START,
        AutomationActionKind.RAID_REWARD,
        AutomationActionKind.RAID_REFRESH,
        -> RAID_RESPONSE
        AutomationActionKind.RAID_CYCLE_ABORT -> LOCAL_RAID_ABORT
        else -> BATTLE_RESPONSE
    }
}
