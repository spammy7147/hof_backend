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

enum class AutomationModuleType {
    KEY_QUEST,
    TIME_BURN,
    UNION,
    COOLDOWN_ADVENTURE,
    DAILY_ADVENTURE,
    OTHER_QUEST,
    NORMAL_MAP,
}

enum class AutomationActionStatus {
    PLANNED,
    RUNNING,
    SUCCEEDED,
    RETRY_WAIT,
    WAITING_CAPTCHA,
    WAITING_CONFIG,
    FAILED,
}
