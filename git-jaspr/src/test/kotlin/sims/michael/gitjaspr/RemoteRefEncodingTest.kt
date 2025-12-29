package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRefParts
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRefParts
import sims.michael.gitjaspr.RemoteRefEncoding.buildRemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteNamedStackRefParts
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts

class RemoteRefEncodingTest {
    @Test
    fun `getRemoteRefParts - no revision number`() {
        assertEquals(
            RemoteRefParts("main", "12345", null),
            getRemoteRefParts("jaspr/main/12345", "jaspr"),
        )
    }

    @Test
    fun `getRemoteRefParts - with revision number`() {
        assertEquals(
            RemoteRefParts("main", "12345", 1),
            getRemoteRefParts("jaspr/main/12345_01", "jaspr"),
        )
    }

    @Test
    fun `buildRemoteNamedStackRef - default prefix and target`() {
        assertEquals("jaspr-named/main/my-stack", buildRemoteNamedStackRef("my-stack"))
    }

    @Test
    fun `buildRemoteNamedStackRef - custom target`() {
        assertEquals(
            "jaspr-named/develop/feature-stack",
            buildRemoteNamedStackRef("feature-stack", "develop"),
        )
    }

    @Test
    fun `buildRemoteNamedStackRef - custom prefix`() {
        assertEquals(
            "custom-prefix/main/test-stack",
            buildRemoteNamedStackRef("test-stack", prefix = "custom-prefix"),
        )
    }

    @Test
    fun `getRemoteNamedStackRefParts - valid ref`() {
        assertEquals(
            RemoteNamedStackRefParts("main", "my-stack"),
            getRemoteNamedStackRefParts("jaspr-named/main/my-stack", "jaspr-named"),
        )
    }

    @Test
    fun `getRemoteNamedStackRefParts - different target`() {
        assertEquals(
            RemoteNamedStackRefParts("develop", "feature-stack"),
            getRemoteNamedStackRefParts("jaspr-named/develop/feature-stack", "jaspr-named"),
        )
    }

    @Test
    fun `getRemoteNamedStackRefParts - custom prefix`() {
        assertEquals(
            RemoteNamedStackRefParts("main", "test-stack"),
            getRemoteNamedStackRefParts("custom/main/test-stack", "custom"),
        )
    }

    @Test
    fun `getRemoteNamedStackRefParts - invalid ref returns null`() {
        assertNull(getRemoteNamedStackRefParts("jaspr/main/12345", "jaspr-named"))
    }

    @Test
    fun `getRemoteNamedStackRefParts - stack name with slashes`() {
        assertEquals(
            RemoteNamedStackRefParts("main", "my/nested/stack"),
            getRemoteNamedStackRefParts("jaspr-named/main/my/nested/stack", "jaspr-named"),
        )
    }
}
