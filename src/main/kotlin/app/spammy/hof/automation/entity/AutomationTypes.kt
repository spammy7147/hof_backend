package app.spammy.hof.automation.entity

enum class AutomationJobStatus {
    PENDING,
    RUNNING,
    WAITING_CAPTCHA,
    WAITING_CONFIG,
    WAITING_LOGIN,
    PAUSED,
    CANCELLED,
    COMPLETED,
    FAILED,
}

enum class AutomationActionStatus {
    PLANNED,
    RUNNING,
    SUCCEEDED,
    RETRY_WAIT,
    WAITING_CAPTCHA,
    WAITING_CONFIG,
    ABORTED,
    FAILED,
}
