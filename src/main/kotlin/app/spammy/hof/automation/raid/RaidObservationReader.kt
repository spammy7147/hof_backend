package app.spammy.hof.automation.raid

fun interface RaidObservationReader {
    fun read(accountId: Long): RaidObservation
}
