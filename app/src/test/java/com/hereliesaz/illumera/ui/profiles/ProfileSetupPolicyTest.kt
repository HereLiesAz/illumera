package com.hereliesaz.illumera.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSetupPolicyTest {
    @Test
    fun disabled_keeps_existing_avatar_choice() {
        assertNull(resolveSetupAccountAvatarRef(false, "https://example.com/avatar.jpg"))
    }

    @Test
    fun enabled_wraps_stremio_avatar_as_remote_profile_avatar() {
        assertEquals(
            "url:https://example.com/avatar.jpg",
            resolveSetupAccountAvatarRef(true, "  https://example.com/avatar.jpg  ")
        )
    }

    @Test
    fun missing_avatar_keeps_existing_avatar_choice() {
        assertNull(resolveSetupAccountAvatarRef(true, null))
        assertNull(resolveSetupAccountAvatarRef(true, "   "))
    }

    @Test
    fun non_web_avatar_url_is_rejected() {
        assertNull(resolveSetupAccountAvatarRef(true, "file:///tmp/avatar.jpg"))
        assertNull(resolveSetupAccountAvatarRef(true, "javascript:alert(1)"))
    }
}
