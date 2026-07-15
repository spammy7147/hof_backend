package app.spammy.hof.account.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class HofSessionRecoveryServiceTest {
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val recoveryService = HofSessionRecoveryService(accountService)

    @Test
    fun expiredActionReauthenticatesWithStoredCredentialsAndRetriesExactlyOnce() {
        var attempts = 0

        val result = recoveryService.execute(7L) {
            attempts += 1
            if (attempts == 1) throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        Mockito.verify(accountService).reauthenticate(7L)
    }

    @Test
    fun nonSessionErrorsAreNotRetried() {
        var attempts = 0

        val error = assertFailsWith<ApiException> {
            recoveryService.execute(7L) {
                attempts += 1
                throw ApiException(ErrorCode.HOF_LOGIN_FAILED, "rejected")
            }
        }

        assertEquals(ErrorCode.HOF_LOGIN_FAILED, error.errorCode)
        assertEquals(1, attempts)
        Mockito.verifyNoInteractions(accountService)
    }
}
