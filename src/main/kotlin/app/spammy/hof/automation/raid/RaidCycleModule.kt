package app.spammy.hof.automation.raid

interface RaidCycleModule {
    fun decideNext(accountId: Long): RaidDirective

    fun decide(accountId: Long): RaidDecision = RaidDecision(decideNext(accountId))

    fun recordObservedResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
    ): RaidRecordResult
}
