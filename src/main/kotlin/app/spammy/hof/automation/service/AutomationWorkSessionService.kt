package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.config.AutomationSessionProperties
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

fun interface AutomationWorkTracker {
    fun ensureForAction(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): AutomationWorkSessionEntity?
}

interface AutomationWorkLifecycle {
    fun resumeForCheck(accountId: Long, sessionId: Long)
    fun triggerCheck(accountId: Long, sessionId: Long)
    fun yieldForPriority(accountId: Long, sessionId: Long): Boolean
    fun recordQuestVictories(accountId: Long, sessionId: Long, victories: Int)
    fun reconcileQuestProgress(accountId: Long, sessionId: Long, current: Int, required: Int)
    fun waitForResource(accountId: Long, sessionId: Long, materialName: String, missingCount: Int?)
    fun waitForUnknownCooldown(accountId: Long, sessionId: Long)
    fun waitForCooldown(accountId: Long, sessionId: Long, nextCheckAt: java.time.Instant)
    fun waitForRaid(
        accountId: Long,
        entryId: Long,
        raidId: String,
        nextCheckAt: java.time.Instant?,
    )
    fun triggerRaidConfigurationCheck(accountId: Long)
    fun complete(accountId: Long, sessionId: Long)
    fun completeBattleMapAction(accountId: Long, entryId: Long, categoryId: String, mapCode: String)
    fun completeAdventureAction(accountId: Long, entryId: Long, categoryId: String, mapCode: String)
    fun completeRaidCycle(accountId: Long, entryId: Long)
    fun stopForConfigurationChange(
        accountId: Long,
        entryId: Long,
        targetKeys: Set<String>,
        wholeEntry: Boolean,
    )
}

@Service
class AutomationWorkSessionService(
    private val typed: TypedAutomationQueryRepository,
    private val queries: AutomationWorkSessionQueryRepository,
    private val commands: AutomationWorkSessionCommandRepository,
    private val timeProvider: TimeProvider,
    private val properties: AutomationSessionProperties = AutomationSessionProperties(),
) : AutomationWorkTracker, AutomationWorkLifecycle {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun resumeForCheck(accountId: Long, sessionId: Long) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        typed.findEntry(accountId, session.entry.id)
            ?: throw AutomationConfigurationException("Automation entry ${session.entry.id} is missing.")
        require(
            session.status == AutomationWorkStatus.YIELDED_PRIORITY ||
                session.status == AutomationWorkStatus.WAITING_COOLDOWN ||
                session.status == AutomationWorkStatus.WAITING_RESOURCE,
        ) { "Only a parked work session may be resumed." }
        session.status = AutomationWorkStatus.RUNNING
        session.nextCheckAt = null
        session.finishedAt = null
        session.updatedAt = timeProvider.now()
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun triggerCheck(accountId: Long, sessionId: Long) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(
            session.status == AutomationWorkStatus.WAITING_COOLDOWN ||
                session.status == AutomationWorkStatus.WAITING_RESOURCE,
        ) { "Only a timed waiting session may be triggered." }
        val now = timeProvider.now()
        session.nextCheckAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun ensureForAction(
        accountId: Long,
        entryId: Long,
        action: PreparedAutomationAction,
    ): AutomationWorkSessionEntity {
        requireRunningRuntime(accountId)
        val entry = typed.findEntry(accountId, entryId)
            ?: throw AutomationConfigurationException("Automation entry $entryId is missing.")
        val spec = action.toWorkSpec(entryId)
        val open = queries.lockOpen(accountId)
        open.firstOrNull { it.status == AutomationWorkStatus.RUNNING }?.let { running ->
            check(running.matches(entryId, spec)) {
                "A different automation work session is already running for account $accountId."
            }
            running.alignRaidTarget(spec, entry.updatedAt.toString())?.let(commands::save)
            return running
        }
        open.firstOrNull { it.matches(entryId, spec) }?.let { parked ->
            require(parked.configVersion == entry.updatedAt.toString()) {
                "A parked work session belongs to an older automation configuration."
            }
            parked.status = AutomationWorkStatus.RUNNING
            parked.nextCheckAt = null
            parked.updatedAt = timeProvider.now()
            parked.alignRaidTarget(spec, entry.updatedAt.toString())
            commands.save(parked)
            return parked
        }
        val now = timeProvider.now()
        val session = AutomationWorkSessionEntity(
            account = entry.account,
            entry = entry,
            workType = spec.type,
            targetKey = spec.targetKey,
            status = AutomationWorkStatus.RUNNING,
            configVersion = entry.updatedAt.toString(),
            targetCount = spec.targetCount,
            questCycle = spec.questCycle,
            missionKey = spec.missionKey,
            missionType = spec.missionType,
            observedCurrent = spec.observedCurrent,
            observedRequired = spec.observedRequired,
            createdAt = now,
            updatedAt = now,
        )
        commands.save(session)
        return session
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun yieldForPriority(accountId: Long, sessionId: Long): Boolean {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.workType == AutomationWorkType.BATTLE_MAP) {
            "Only a battle-map work session may yield for a higher priority target."
        }
        if (session.status != AutomationWorkStatus.RUNNING) return false
        session.status = AutomationWorkStatus.YIELDED_PRIORITY
        session.nextCheckAt = null
        session.updatedAt = timeProvider.now()
        commands.save(session)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordQuestVictories(accountId: Long, sessionId: Long, victories: Int) {
        require(victories >= 0)
        if (victories == 0) return
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(
            session.workType == AutomationWorkType.QUEST &&
                session.status == AutomationWorkStatus.RUNNING &&
                session.missionType == app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR.name,
        )
        val current = requireNotNull(session.observedCurrent) { "Map-clear session has no observed progress." }
        session.confirmedCount += victories
        session.observedCurrent = session.observedRequired
            ?.let { required -> (current + victories).coerceAtMost(required) }
            ?: current + victories
        session.updatedAt = timeProvider.now()
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun reconcileQuestProgress(accountId: Long, sessionId: Long, current: Int, required: Int) {
        require(current >= 0 && required >= 0 && current <= required)
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(
            session.workType == AutomationWorkType.QUEST &&
                session.status == AutomationWorkStatus.RUNNING &&
                session.missionType == app.spammy.hof.quest.model.QuestMissionType.MAP_CLEAR.name,
        )
        val now = timeProvider.now()
        session.observedCurrent = current
        session.observedRequired = required
        session.lastVerifiedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitForResource(accountId: Long, sessionId: Long, materialName: String, missingCount: Int?) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.workType == AutomationWorkType.QUEST && session.status == AutomationWorkStatus.RUNNING)
        val now = timeProvider.now()
        session.status = AutomationWorkStatus.WAITING_RESOURCE
        session.materialName = materialName
        session.materialMissing = missingCount
        session.nextCheckAt = reconciliationAt(accountId, now)
        session.lastVerifiedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitForUnknownCooldown(accountId: Long, sessionId: Long) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.workType == AutomationWorkType.QUEST && session.status == AutomationWorkStatus.RUNNING)
        val now = timeProvider.now()
        session.status = AutomationWorkStatus.WAITING_COOLDOWN
        session.nextCheckAt = reconciliationAt(accountId, now)
        session.lastVerifiedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitForCooldown(accountId: Long, sessionId: Long, nextCheckAt: java.time.Instant) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        require(session.status == AutomationWorkStatus.RUNNING)
        session.status = AutomationWorkStatus.WAITING_COOLDOWN
        session.nextCheckAt = nextCheckAt
        session.updatedAt = timeProvider.now()
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun waitForRaid(
        accountId: Long,
        entryId: Long,
        raidId: String,
        nextCheckAt: java.time.Instant?,
    ) {
        requireRunningRuntime(accountId)
        val entry = typed.findEntry(accountId, entryId)
            ?: throw AutomationConfigurationException("Automation entry $entryId is missing.")
        require(entry.type == app.spammy.hof.automation.entity.AutomationType.RAID) {
            "Only a raid entry may create a raid wait session."
        }
        val now = timeProvider.now()
        val open = queries.lockOpen(accountId)
        val session = open.singleOrNull {
            it.entry.id == entryId && it.workType == AutomationWorkType.RAID
        }
        if (session != null) {
            require(session.status in OPEN_SESSION_STATUSES)
            session.targetKey = raidId
            session.status = AutomationWorkStatus.WAITING_COOLDOWN
            session.configVersion = entry.updatedAt.toString()
            session.nextCheckAt = nextCheckAt
            session.finishedAt = null
            session.updatedAt = now
            commands.save(session)
            return
        }
        commands.save(
            AutomationWorkSessionEntity(
                account = entry.account,
                entry = entry,
                workType = AutomationWorkType.RAID,
                targetKey = raidId,
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = entry.updatedAt.toString(),
                nextCheckAt = nextCheckAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun triggerRaidConfigurationCheck(accountId: Long) {
        val now = timeProvider.now()
        queries.lockOpen(accountId)
            .filter {
                it.workType == AutomationWorkType.RAID &&
                    it.status == AutomationWorkStatus.WAITING_COOLDOWN
            }
            .forEach { session ->
                session.nextCheckAt = now
                session.updatedAt = now
                commands.save(session)
            }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun complete(accountId: Long, sessionId: Long) {
        requireRunningRuntime(accountId)
        val session = requireSession(accountId, sessionId)
        if (session.status == AutomationWorkStatus.COMPLETED) return
        require(session.status in OPEN_SESSION_STATUSES)
        val now = timeProvider.now()
        session.status = AutomationWorkStatus.COMPLETED
        session.nextCheckAt = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeBattleMapAction(
        accountId: Long,
        entryId: Long,
        categoryId: String,
        mapCode: String,
    ) {
        requireRunningRuntime(accountId)
        val targetKey = "$categoryId/$mapCode"
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.BATTLE_MAP &&
                it.targetKey == targetKey
        } ?: return
        val now = timeProvider.now()
        session.status = AutomationWorkStatus.COMPLETED
        session.nextCheckAt = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeAdventureAction(
        accountId: Long,
        entryId: Long,
        categoryId: String,
        mapCode: String,
    ) {
        requireRunningRuntime(accountId)
        val targetKey = "$categoryId/$mapCode"
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.ADVENTURE_MAP &&
                it.targetKey == targetKey
        } ?: return
        val now = timeProvider.now()
        session.status = AutomationWorkStatus.COMPLETED
        session.nextCheckAt = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun completeRaidCycle(accountId: Long, entryId: Long) {
        requireRunningRuntime(accountId)
        val session = queries.lockOpen(accountId).singleOrNull {
            it.status == AutomationWorkStatus.RUNNING &&
                it.entry.id == entryId &&
                it.workType == AutomationWorkType.RAID
        } ?: return
        val now = timeProvider.now()
        session.status = AutomationWorkStatus.COMPLETED
        session.nextCheckAt = null
        session.finishedAt = now
        session.updatedAt = now
        commands.save(session)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun stopForConfigurationChange(
        accountId: Long,
        entryId: Long,
        targetKeys: Set<String>,
        wholeEntry: Boolean,
    ) {
        val now = timeProvider.now()
        queries.lockOpen(accountId)
            .filter { it.entry.id == entryId && (wholeEntry || it.targetKey in targetKeys) }
            .forEach { session ->
                session.status = AutomationWorkStatus.STOPPED
                session.nextCheckAt = null
                session.finishedAt = now
                session.updatedAt = now
                commands.save(session)
            }
    }

    private fun requireRunningRuntime(accountId: Long) {
        val runtime = typed.lockRuntimeState(accountId)
            ?: throw IllegalStateException("Automation runtime does not exist for account $accountId.")
        check(runtime.lifecycleStatus in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)) {
            "Automation runtime is not running for account $accountId."
        }
    }

    private fun requireSession(accountId: Long, sessionId: Long): AutomationWorkSessionEntity =
        queries.lockById(accountId, sessionId)
            ?: throw IllegalArgumentException("Work session $sessionId does not belong to account $accountId.")

    private fun reconciliationAt(accountId: Long, now: java.time.Instant): java.time.Instant {
        val jitterBound = properties.reconciliationJitter.seconds.coerceAtLeast(0)
        val jitterSeconds = if (jitterBound == 0L) 0L else Math.floorMod(accountId, jitterBound + 1)
        return now.plus(properties.reconciliationInterval).plusSeconds(jitterSeconds)
    }

    private fun PreparedAutomationAction.toWorkSpec(entryId: Long): WorkSpec = when (this) {
        is QuestAction.Claim -> WorkSpec(AutomationWorkType.QUEST, questKey)
        is QuestAction.Accept -> WorkSpec(AutomationWorkType.QUEST, questKey)
        is HomeQuestAutomationAction -> WorkSpec(AutomationWorkType.HOME_QUEST, questId)
        is QuestAction.Battle -> WorkSpec(
            type = AutomationWorkType.QUEST,
            targetKey = questKey,
            questCycle = questCycle,
            missionKey = missionKey,
            missionType = missionType.name,
            observedCurrent = missionCurrent,
            observedRequired = missionRequired,
        )
        is BattleMapAutomationAction -> {
            val target = "$categoryId/$mapCode"
            when (source) {
                BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> {
                    val configuredTarget = typed.findBattleSettings(entryId)
                        .singleOrNull { "${it.categoryId}/${it.mapCode}" == target }
                        ?.dailyTargetCount
                        ?: throw AutomationConfigurationException("Battle-map target $target is missing or duplicated.")
                    WorkSpec(AutomationWorkType.BATTLE_MAP, target, targetCount = configuredTarget)
                }
                BattleAutomationActionSource.UNION_AUTOMATION -> WorkSpec(AutomationWorkType.UNION, target)
                BattleAutomationActionSource.FISHING_AUTOMATION -> WorkSpec(AutomationWorkType.FISHING, FISHING_CYCLE_TARGET)
                BattleAutomationActionSource.RAID_AUTOMATION -> WorkSpec(
                    AutomationWorkType.RAID,
                    sourceTargetKey ?: target,
                )
                BattleAutomationActionSource.QUEST_AUTOMATION -> WorkSpec(AutomationWorkType.QUEST, target)
                BattleAutomationActionSource.ADVENTURE_AUTOMATION -> WorkSpec(AutomationWorkType.ADVENTURE_MAP, target)
            }
        }
        is AdventureMapAutomationAction -> WorkSpec(AutomationWorkType.ADVENTURE_MAP, "$categoryId/$mapCode")
        is FishingTownAutomationAction -> WorkSpec(AutomationWorkType.FISHING, FISHING_CYCLE_TARGET)
        is RaidTownAutomationAction -> WorkSpec(AutomationWorkType.RAID, targetRaidId ?: raidId ?: action.name)
        is RaidCycleAbortAutomationAction -> WorkSpec(AutomationWorkType.RAID, raidId)
    }

    private data class WorkSpec(
        val type: AutomationWorkType,
        val targetKey: String,
        val targetCount: Int? = null,
        val questCycle: String? = null,
        val missionKey: String? = null,
        val missionType: String? = null,
        val observedCurrent: Int? = null,
        val observedRequired: Int? = null,
    )

    private fun AutomationWorkSessionEntity.matches(entryId: Long, spec: WorkSpec): Boolean =
        entry.id == entryId &&
            workType == spec.type &&
            (targetKey == spec.targetKey || workType in setOf(AutomationWorkType.FISHING, AutomationWorkType.RAID))

    private fun AutomationWorkSessionEntity.alignRaidTarget(
        spec: WorkSpec,
        latestConfigVersion: String,
    ): AutomationWorkSessionEntity? {
        if (workType != AutomationWorkType.RAID || targetKey == spec.targetKey) return null
        targetKey = spec.targetKey
        configVersion = latestConfigVersion
        updatedAt = timeProvider.now()
        return this
    }

    private companion object {
        const val FISHING_CYCLE_TARGET = "DAILY_FISHING"
        val OPEN_SESSION_STATUSES = setOf(
            AutomationWorkStatus.RUNNING,
            AutomationWorkStatus.WAITING_COOLDOWN,
            AutomationWorkStatus.WAITING_RESOURCE,
            AutomationWorkStatus.YIELDED_PRIORITY,
        )
    }
}
