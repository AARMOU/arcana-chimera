package com.arcanatable

import org.junit.Assert.*
import org.junit.Test

class ChimeraLegalSourceTest {
    @Test fun unpublishedSourceIsNeverReportedAsAvailable() {
        val text = "SOURCE_VERSION_NAME=UNPUBLISHED\nSOURCE_URL=UNPUBLISHED\nSOURCE_SHA256=UNPUBLISHED"
        assertFalse(ChimeraLegalSource.parse(text, "0.35.0-chimera-143").published)
    }

    @Test fun versionMismatchOrInsecureUrlIsRejected() {
        val sha = "a".repeat(64)
        val text = "SOURCE_VERSION_NAME=v144\nSOURCE_URL=https://example.org/v144/source.tar.gz\nSOURCE_SHA256=" + sha
        assertFalse(ChimeraLegalSource.parse(text, "v143").published)
        assertFalse(ChimeraLegalSource.parse(
            text.replace("https://", "http://"), "v144").published)
    }

    @Test fun onlyExactVersionHttpsAndDigestCanBeDisplayedAsPublished() {
        val sha = "b".repeat(64)
        val text = "SOURCE_VERSION_NAME=v144\nSOURCE_URL=https://example.org/v144/source.tar.gz\nSOURCE_SHA256=" + sha
        val result = ChimeraLegalSource.parse(text, "v144")
        assertTrue(result.published)
        assertEquals(sha, result.sha256)
        assertFalse(ChimeraLegalSource.parse(text.replace(sha, "bad"), "v144").published)
    }

    @Test fun versionMatchedHttpsLocationWithoutDigestIsPublished() {
        val text = "SOURCE_VERSION_NAME=v144\nSOURCE_URL=https://example.org/releases/v144\n"
        val result = ChimeraLegalSource.parse(text, "v144")
        assertTrue(result.published)
        assertEquals("", result.sha256)
        assertFalse(ChimeraLegalSource.parse(text, "v145").published)
    }
}
