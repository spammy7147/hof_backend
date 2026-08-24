package app.spammy.hof.automation.raid

interface RaidCycleModule {
    fun decide(accountId: Long): RaidDecision

    fun recordObservedResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
    ): RaidRecordResult
}
