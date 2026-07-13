package app.spammy.hof.automation.service

object AutomationActionContext {
    private val current = ThreadLocal<Long?>()

    fun currentActionId(): Long? = current.get()

    fun <T> withAction(actionId: Long, block: () -> T): T {
        current.set(actionId)
        return try {
            block()
        } finally {
            current.remove()
        }
    }
}
