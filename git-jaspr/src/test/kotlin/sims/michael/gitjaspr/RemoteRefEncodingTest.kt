package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef

class RemoteRefEncodingTest {
    @Test
    fun `ref parse - no revision number`() {
        assertEquals(
            RemoteRef("12345", "main", "jaspr"),
            RemoteRef.parse("jaspr/main/12345", "jaspr"),
        )
    }

    @Test
    fun `ref parse - with revision number`() {
        assertEquals(
            RemoteRef("12345", "main", "jaspr", 1),
            RemoteRef.parse("jaspr/main/12345_01", "jaspr"),
        )
    }

    @Test
    fun `named stack ref name - default prefix and target`() {
        assertEquals(
            "jaspr-named/main/my-stack",
            RemoteNamedStackRef(stackName = "my-stack", targetRef = DEFAULT_TARGET_REF).name(),
        )
    }

    @Test
    fun `named stack ref name - custom target`() {
        assertEquals(
            "jaspr-named/develop/feature-stack",
            RemoteNamedStackRef(stackName = "feature-stack", targetRef = "develop").name(),
        )
    }

    @Test
    fun `named stack ref name - custom prefix`() {
        assertEquals(
            "custom-prefix/main/test-stack",
            RemoteNamedStackRef(
                    stackName = "test-stack",
                    targetRef = DEFAULT_TARGET_REF,
                    prefix = "custom-prefix",
                )
                .name(),
        )
    }

    @Test
    fun `named stack ref parse - valid ref`() {
        assertEquals(
            RemoteNamedStackRef("my-stack", "main", "jaspr-named"),
            RemoteNamedStackRef.parse("jaspr-named/main/my-stack", "jaspr-named"),
        )
    }

    @Test
    fun `named stack ref parse - different target`() {
        assertEquals(
            RemoteNamedStackRef("feature-stack", "develop", "jaspr-named"),
            RemoteNamedStackRef.parse("jaspr-named/develop/feature-stack", "jaspr-named"),
        )
    }

    @Test
    fun `named stack ref parse - custom prefix`() {
        assertEquals(
            RemoteNamedStackRef("test-stack", "main", "custom"),
            RemoteNamedStackRef.parse("custom/main/test-stack", "custom"),
        )
    }

    @Test
    fun `named stack ref parse - invalid ref returns null`() {
        assertNull(RemoteNamedStackRef.parse("jaspr/main/12345", "jaspr-named"))
    }

    @Test
    fun `named stack ref parse - stack name with slashes`() {
        assertEquals(
            RemoteNamedStackRef("my/nested/stack", "main", "jaspr-named"),
            RemoteNamedStackRef.parse("jaspr-named/main/my/nested/stack", "jaspr-named"),
        )
    }
}
