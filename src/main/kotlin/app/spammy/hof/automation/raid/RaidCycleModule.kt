package app.spammy.hof.automation.raid

interface RaidCycleModule {
    fun decideNext(accountId: Long): RaidDirective

    fun recordObservedResult(
        accountId: Long,
        attempt: RaidAttempt,
        observation: RaidResultObservation,
    ): RaidRecordResult
}
