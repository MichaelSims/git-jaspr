package sims.michael.gitjaspr

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteNamedStackRef
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRef

class RemoteRefEncodingTest {
    @Test
    fun `ref parse - no revision number`() {
        assertEquals(
            expected = RemoteRef("12345", "main", "jaspr"),
            actual = RemoteRef.parse("jaspr/main/12345", "jaspr"),
        )
    }

    @Test
    fun `ref parse - with revision number`() {
        assertEquals(
            expected = RemoteRef("12345", "main", "jaspr", 1),
            actual = RemoteRef.parse("jaspr/main/12345_01", "jaspr"),
        )
    }

    @Test
    fun `named stack ref name - default prefix and target`() {
        assertEquals(
            expected = "jaspr-named/main/my-stack",
            actual =
                RemoteNamedStackRef(stackName = "my-stack", targetRef = DEFAULT_TARGET_REF).name(),
        )
    }

    @Test
    fun `named stack ref name - custom target`() {
        assertEquals(
            expected = "jaspr-named/develop/feature-stack",
            actual = RemoteNamedStackRef(stackName = "feature-stack", targetRef = "develop").name(),
        )
    }

    @Test
    fun `named stack ref name - custom prefix`() {
        assertEquals(
            expected = "custom-prefix/main/test-stack",
            actual =
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
            expected = RemoteNamedStackRef("my-stack", "main", "jaspr-named"),
            actual = RemoteNamedStackRef.parse("jaspr-named/main/my-stack", "jaspr-named"),
        )
    }

    @Test
    fun `named stack ref parse - different target`() {
        assertEquals(
            expected = RemoteNamedStackRef("feature-stack", "develop", "jaspr-named"),
            actual = RemoteNamedStackRef.parse("jaspr-named/develop/feature-stack", "jaspr-named"),
        )
    }

    @Test
    fun `named stack ref parse - custom prefix`() {
        assertEquals(
            expected = RemoteNamedStackRef("test-stack", "main", "custom"),
            actual = RemoteNamedStackRef.parse("custom/main/test-stack", "custom"),
        )
    }

    @Test
    fun `named stack ref parse - invalid ref returns null`() {
        assertNull(RemoteNamedStackRef.parse("jaspr/main/12345", "jaspr-named"))
    }

    @Test
    fun `named stack ref parse - stack name with slashes`() {
        assertEquals(
            expected = RemoteNamedStackRef("my/nested/stack", "main", "jaspr-named"),
            actual = RemoteNamedStackRef.parse("jaspr-named/main/my/nested/stack", "jaspr-named"),
        )
    }
}
