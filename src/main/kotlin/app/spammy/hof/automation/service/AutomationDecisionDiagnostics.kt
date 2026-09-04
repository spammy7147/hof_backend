package app.spammy.hof.automation.service

import app.spammy.hof.automation.raid.RaidAuthoritativeState
import java.time.Instant
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 재판단으로 덮어쓰지 않을 판단 입력. 원문 응답, form 값, 파티 요청 payload는 포함하지 않는다. */
internal object AutomationDecisionDiagnostics {
    private val mapper = jacksonObjectMapper()
    private const val TARGET_LIMIT = 30

    fun capture(
        stage: String,
        capturedAt: Instant,
        snapshot: AutomationEntrySnapshot? = null,
        raid: RaidAuthoritativeState? = null,
        error: Exception? = null,
        workSessionId: Long? = null,
        targetKey: String? = null,
        scope: String? = null,
    ): String {
        val context = linkedMapOf<String, Any?>(
            "version" to 1,
            "buildVersion" to (javaClass.`package`.implementationVersion ?: "local"),
            "stage" to stage,
            "capturedAt" to capturedAt.toString(),
            "workSessionId" to workSessionId,
            "targetKey" to targetKey,
            "scope" to scope,
            "targetLimit" to TARGET_LIMIT,
            "snapshot" to snapshot?.summary(),
            "raid" to raid,
            // 예외 메시지에는 URL, 응답 원문, 인증 정보가 섞일 수 있어 클래스와 호출 위치만 보존한다.
            "errors" to error?.let {
                generateSequence<Throwable>(it) { cause -> cause.cause }.take(8).map { cause ->
                    mapOf("type" to cause.javaClass.name, "locations" to cause.stackTrace.take(12).map { it.toString() })
                }.toList()
            },
        )
        val json = mapper.writeValueAsString(context)
        return if (json.length <= 16_384) json else mapper.writeValueAsString(mapOf(
            "version" to 1, "stage" to stage, "truncated" to true, "contextPrefix" to json.take(14_000),
        ))
    }

    private fun AutomationEntrySnapshot.summary(): Map<String, Any?> = when {
        homeQuest != null -> homeQuest.let { state -> mapOf(
            "observedAt" to state.now, "time" to state.timeSnapshot,
            "workSessionId" to state.workSessionId, "workSessionRevision" to state.workSessionRevision,
            "targetCount" to state.quests.size,
            "selections" to state.selections.take(TARGET_LIMIT).map {
                mapOf("questId" to it.questId, "enabled" to it.enabled, "order" to it.sourceOrder)
            },
            "quests" to state.quests.take(TARGET_LIMIT).map {
                mapOf("questId" to it.id, "state" to it.state, "actionPresent" to (it.actionId != null))
            },
        ) }
        quest != null -> quest.let { state -> mapOf(
            "observedAt" to state.now, "pageComplete" to state.pageComplete, "time" to state.timeSnapshot,
            "workSessionId" to state.workSessionId, "workSessionRevision" to state.workSessionRevision,
            "primaryPresetId" to state.primaryPresetId, "partyResolved" to (state.primaryParty != null),
            "targetCount" to state.quests.size,
            "selections" to state.selections.take(TARGET_LIMIT).map { selection ->
                mapOf("questKey" to selection.questKey, "enabled" to selection.enabled,
                    "maps" to selection.maps.take(TARGET_LIMIT).map {
                        mapOf("missionKey" to it.missionKey, "categoryId" to it.categoryId, "mapCode" to it.mapCode,
                            "order" to it.executionOrder, "presetMode" to it.preset.mode,
                            "presetId" to it.preset.presetId, "resolvedPresetId" to it.preset.resolvedPresetId,
                            "resolutionChecked" to it.preset.resolutionChecked,
                            "partyResolved" to (it.preset.resolvedParty != null))
                    })
            },
            "quests" to state.quests.take(TARGET_LIMIT).map {
                mapOf("questKey" to it.questKey, "state" to it.state, "actionPresent" to (it.actionNo != null),
                    "missions" to it.missions.take(TARGET_LIMIT).map { mission ->
                        mapOf("type" to mission.type, "progress" to mission.progress, "completable" to mission.completable)
                    })
            },
            "maps" to state.mapStates.take(TARGET_LIMIT).map { it.copy(mapName = "") },
        ) }
        battle != null -> battle.let { state -> mapOf(
            "observedAt" to state.evaluationInstant, "time" to state.timeSnapshot,
            "minimumRemainingTime" to state.minimumRemainingTime, "primaryPresetId" to state.primaryPresetId,
            "availablePresetIds" to state.availablePresetIds.take(TARGET_LIMIT),
            "resolvedPresetIds" to state.resolvedParties.keys.take(TARGET_LIMIT),
            "targetCount" to state.settings.size, "settings" to state.settings.take(TARGET_LIMIT),
            "maps" to state.mapStates.take(TARGET_LIMIT).map { it.copy(mapName = null) },
            "successfulRuns" to state.successfulRuns.entries.take(TARGET_LIMIT).map {
                mapOf("categoryId" to it.key.categoryId, "mapCode" to it.key.mapCode, "count" to it.value)
            },
        ) }
        adventure != null -> adventure.let { state -> mapOf(
            "observedAt" to state.evaluationInstant, "time" to state.timeSnapshot,
            "targetCount" to state.settings.size, "settings" to state.settings.take(TARGET_LIMIT),
            "maps" to state.mapStates.take(TARGET_LIMIT).map { it.copy(mapName = null) },
            "presets" to state.presetResolutions.entries.take(TARGET_LIMIT).map {
                val valid = it.value as? AdventureMapPresetResolution.Valid
                mapOf("settingIdentity" to it.key, "valid" to (valid != null),
                    "presetId" to valid?.resolvedPresetId, "partyResolved" to (valid?.resolvedParty != null))
            },
        ) }
        union != null -> union.let { state -> mapOf(
            "observedAt" to state.now, "currentTargetKey" to state.currentTargetKey,
            "targetCount" to state.settings.size,
            "settings" to state.settings.take(TARGET_LIMIT).map {
                mapOf("targetKey" to it.targetKey, "order" to it.executionOrder,
                    "presetId" to it.presetId, "partyResolved" to (it.resolvedParty != null))
            },
            "maps" to state.states.take(TARGET_LIMIT).map { it.copy(mapName = null) },
        ) }
        fishing != null -> fishing.let { snapshot -> snapshot.state.let { state -> mapOf(
            "observedAt" to snapshot.now, "primaryAction" to state.primaryAction,
            "availableActions" to state.availableActions, "remainingCasts" to state.remainingCasts,
            "baitCount" to state.baitCount, "escapeSeconds" to state.escapeSeconds,
            "blockedByBattle" to state.blockedByBattle,
            "battleCategoryId" to state.battleTarget?.categoryId, "battleMapCode" to state.battleTarget?.mapCode,
            "primaryPresetId" to snapshot.primaryPreset?.presetId,
            "partyResolved" to (snapshot.primaryPreset?.resolvedParty != null),
            "targetCount" to snapshot.maps.size,
            "maps" to snapshot.maps.take(TARGET_LIMIT).map {
                mapOf("categoryId" to it.categoryId, "mapCode" to it.mapCode,
                    "presetId" to it.presetId, "partyResolved" to (it.resolvedParty != null))
            },
        ) } }
        else -> mapOf("snapshotMissing" to true)
    }
}
