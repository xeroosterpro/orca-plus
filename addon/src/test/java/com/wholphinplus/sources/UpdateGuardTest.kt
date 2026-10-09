package com.wholphinplus.sources

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGuardTest {
    @Test fun `release downloads from GitHub over https are allowed`() {
        assertTrue(UpdateGuard.isAllowedUrl("https://github.com/owner/repo/releases/download/v1-r2/Wholphin-release.apk"))
        assertTrue(UpdateGuard.isAllowedUrl("https://objects.githubusercontent.com/github-production-release-asset/1/2?x=y"))
        assertTrue(UpdateGuard.isAllowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/2"))
        assertTrue(UpdateGuard.isAllowedUrl("HTTPS://GitHub.com:443/a.apk"))
    }

    @Test fun `anything else is refused`() {
        listOf(
            null,
            "",
            "not a url",
            "http://github.com/owner/repo/releases/download/x/Wholphin-release.apk",
            "https://github.com.evil.example/a.apk",
            "https://evil.example/github.com/a.apk",
            "https://user@github.com/a.apk",
            "https://github.com:8443/a.apk",
            "ftp://github.com/a.apk",
            "https://raw.githubusercontent.com/a.apk",
        ).forEach { assertFalse(it.toString(), UpdateGuard.isAllowedUrl(it)) }
    }
}
