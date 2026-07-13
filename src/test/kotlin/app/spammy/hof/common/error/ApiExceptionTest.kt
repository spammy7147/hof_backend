package app.spammy.hof.common.error

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiExceptionTest {
    @Test
    fun apiExceptionCarriesCodeAndMessage() {
        val exception = ApiException(ErrorCode.HOF_LOGIN_FAILED, "로그인 실패")

        assertEquals(ErrorCode.HOF_LOGIN_FAILED, exception.errorCode)
        assertEquals("로그인 실패", exception.message)
    }
}
