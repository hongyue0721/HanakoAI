package `fun`.kirari.hanako.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ConnectionTestManager] 单元测试。
 *
 * 验证内容：
 * - 状态按 providerId 隔离（不串页）
 * - reset 单个 provider
 * - 失败结果正确传递
 * - 默认值
 */
class ConnectionTestManagerTest {

    @Test
    fun stateFor_returnsIdleDefaultForUnknownProvider() {
        val manager = ConnectionTestManager()
        val state = manager.stateFor("unknown-id")
        assertEquals(ConnectionTestStatus.IDLE, state.status)
        assertEquals(0, state.latencyMs)
        assertEquals("", state.errorMessage)
    }

    @Test
    fun setState_isIsolatedPerProvider() {
        val manager = ConnectionTestManager()
        manager.setState("provider-a", ConnectionTestState(status = ConnectionTestStatus.TESTING))
        manager.setState("provider-b", ConnectionTestState(status = ConnectionTestStatus.SUCCESS, latencyMs = 42))

        assertEquals(ConnectionTestStatus.TESTING, manager.stateFor("provider-a").status)
        assertEquals(ConnectionTestStatus.SUCCESS, manager.stateFor("provider-b").status)
        assertEquals(42, manager.stateFor("provider-b").latencyMs)
    }

    @Test
    fun setState_doesNotOverwriteOtherProviders() {
        val manager = ConnectionTestManager()
        manager.setState("provider-a", ConnectionTestState(status = ConnectionTestStatus.SUCCESS))
        manager.setState("provider-b", ConnectionTestState(status = ConnectionTestStatus.FAILED, errorMessage = "err"))

        assertEquals(ConnectionTestStatus.SUCCESS, manager.stateFor("provider-a").status)
        assertEquals(ConnectionTestStatus.FAILED, manager.stateFor("provider-b").status)
        assertEquals("err", manager.stateFor("provider-b").errorMessage)
    }

    @Test
    fun reset_clearsStateForSpecificProviderOnly() {
        val manager = ConnectionTestManager()
        manager.setState("provider-a", ConnectionTestState(status = ConnectionTestStatus.SUCCESS))
        manager.setState("provider-b", ConnectionTestState(status = ConnectionTestStatus.FAILED))

        manager.reset("provider-a")

        assertEquals(ConnectionTestStatus.IDLE, manager.stateFor("provider-a").status)
        assertEquals(ConnectionTestStatus.FAILED, manager.stateFor("provider-b").status)
    }

    @Test
    fun resetAll_clearsAllStates() {
        val manager = ConnectionTestManager()
        manager.setState("provider-a", ConnectionTestState(status = ConnectionTestStatus.SUCCESS))
        manager.setState("provider-b", ConnectionTestState(status = ConnectionTestStatus.FAILED))

        manager.resetAll()

        assertEquals(ConnectionTestStatus.IDLE, manager.stateFor("provider-a").status)
        assertEquals(ConnectionTestStatus.IDLE, manager.stateFor("provider-b").status)
    }

    @Test
    fun failedResultWithErrorMessageIsPreserved() {
        val manager = ConnectionTestManager()
        val failedState = ConnectionTestState(
            status = ConnectionTestStatus.FAILED,
            latencyMs = 1500,
            errorMessage = "连接超时"
        )
        manager.setState("provider-x", failedState)

        val retrieved = manager.stateFor("provider-x")
        assertEquals(ConnectionTestStatus.FAILED, retrieved.status)
        assertEquals(1500, retrieved.latencyMs)
        assertEquals("连接超时", retrieved.errorMessage)
    }
}
