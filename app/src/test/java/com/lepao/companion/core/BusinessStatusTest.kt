package com.lepao.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Business status mapping and the "session was kicked" flag on the login health. */
class BusinessStatusTest {

    @Test
    fun businessStatusesUseChineseMessages() {
        assertEquals("登录失败，请重新获取登录态", Protocol.businessMessage(101))
        assertEquals("账号已在其他设备登录，请重新获取登录态", Protocol.businessMessage(102))
        assertEquals("登录续期已被禁用，请重新获取登录态", Protocol.businessMessage(103))
        assertEquals("账号访问受限，请检查账号状态或联系学校", Protocol.businessMessage(106))
        assertTrue(Protocol.businessMessage(108).contains("108"))
        assertTrue(Protocol.businessMessage(null).contains("status=null"))
    }

    @Test
    fun loginHealthReportsARejectedSession() {
        val identity = Identity(linkedMapOf("uid" to "1", "token" to "token-value"))
        val session = SessionContext(identity = identity, capturedAt = 1)

        assertFalse(TokenService.health(session, now = 10).invalidated)

        session.invalidatedAt = 5
        val health = TokenService.health(session, now = 10)
        assertTrue(health.invalidated)
        assertTrue(health.accessTokenPresent)
    }
}
