package app.spammy.hof.automation.convergence

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

enum class AutomationConvergenceMode {
    LEGACY,
    SHADOW,
    ACTIVE,
}

@ConfigurationProperties("hof.automation-convergence")
data class AutomationConvergenceProperties(
    /** 모든 action family가 같은 mode를 사용해 부분 전환을 막는다. */
    val mode: AutomationConvergenceMode = AutomationConvergenceMode.SHADOW,
    /** 긴급 시 자동화 POST만 막고 GET 관측·진단은 유지한다. */
    val automationPostsEnabled: Boolean = true,
)

@Service
class AutomationConvergenceRollout(
    private val properties: AutomationConvergenceProperties,
) {
    val active: Boolean
        get() = properties.mode == AutomationConvergenceMode.ACTIVE

    val shadow: Boolean
        get() = properties.mode == AutomationConvergenceMode.SHADOW

    val automationPostsEnabled: Boolean
        get() = properties.automationPostsEnabled

    val mode: AutomationConvergenceMode
        get() = properties.mode
}
