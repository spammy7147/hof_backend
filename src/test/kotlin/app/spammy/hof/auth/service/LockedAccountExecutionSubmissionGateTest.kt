package app.spammy.hof.auth.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.mockito.Mockito

class LockedAccountExecutionSubmissionGateTest {
    private val authorization = Mockito.mock(AccountExecutionAuthorizationReader::class.java)
    private val service = LockedAccountExecutionSubmissionGate(authorization, InMemoryAccountExecutionLock())

    @Test
    fun `runs the remote submission only while the locked account still has an active app session`() {
        var submitted = false
        Mockito.`when`(authorization.isExecutionAllowed(7L)).thenReturn(true)

        val authorized = service.executeIfAuthorized(7L, Runnable { submitted = true })

        assertTrue(authorized)
        assertTrue(submitted)
    }

    @Test
    fun `does not start a remote submission after logout removed the final active session`() {
        var submitted = false
        Mockito.`when`(authorization.isExecutionAllowed(7L)).thenReturn(false)

        val authorized = service.executeIfAuthorized(7L, Runnable { submitted = true })

        assertFalse(authorized)
        assertFalse(submitted)
    }

    @Test
    fun `logout waits for an entered submission and blocks later submissions before they start`() {
        Mockito.`when`(authorization.isExecutionAllowed(7L)).thenReturn(true, false)
        val submissionEntered = CountDownLatch(1)
        val finishSubmission = CountDownLatch(1)
        val logoutFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> {
                service.executeIfAuthorized(7L, Runnable {
                    submissionEntered.countDown()
                    finishSubmission.await(5, TimeUnit.SECONDS)
                })
            }
            assertTrue(submissionEntered.await(5, TimeUnit.SECONDS))
            val logout = executor.submit {
                service.executeLogout(7L, Runnable { logoutFinished.countDown() })
            }
            assertFalse(logoutFinished.await(100, TimeUnit.MILLISECONDS))

            finishSubmission.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            logout.get(5, TimeUnit.SECONDS)
            assertTrue(logoutFinished.await(1, TimeUnit.SECONDS))
            assertFalse(service.executeIfAuthorized(7L, Runnable { error("must not submit") }))
        } finally {
            executor.shutdownNow()
        }
    }

    private class InMemoryAccountExecutionLock : AccountExecutionLock {
        private val lock = ReentrantReadWriteLock(true)

        override fun executeShared(accountId: Long, action: Runnable) = lock.read { action.run() }

        override fun executeExclusive(accountId: Long, action: Runnable) = lock.write { action.run() }
    }
}
