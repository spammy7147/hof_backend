package app.spammy.hof.automation.service

object AutomationActionContext {
    private val current = ThreadLocal<AutomationExecutionToken?>()

    /** 현재 스레드에서 실행 중인 정확한 action 시도를 반환한다. */
    fun currentToken(): AutomationExecutionToken? = current.get()

    /** 외부 호출과 그 내부 캡차 감지가 같은 실행 시도 토큰을 공유하도록 실행 범위를 연다. */
    fun <T> withToken(token: AutomationExecutionToken, block: () -> T): T {
        val previous = current.get()
        current.set(token)
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
