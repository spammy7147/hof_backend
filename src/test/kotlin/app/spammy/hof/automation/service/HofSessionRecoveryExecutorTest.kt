package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class HofSessionRecoveryExecutorTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val executor = HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService))

    @Test
    fun expiredActionReauthenticatesAndRetriesExactlyOnce() {
        var attempts = 0
        val result = executor.execute(7L) {
            attempts += 1
            if (attempts == 1) throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        Mockito.verify(accountService).reauthenticate(7L)
    }

    @Test
    fun rejectedStoredCredentialsRequireUserLogin() {
        Mockito.`when`(accountService.reauthenticate(7L))
            .thenThrow(ApiException(ErrorCode.HOF_LOGIN_FAILED, "rejected"))

        assertFailsWith<AutomationLoginRequiredException> {
            executor.execute(7L) { throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired") }
        }
    }
}
