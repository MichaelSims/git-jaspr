package sims.michael.gitjaspr.testing

import sims.michael.gitjaspr.Ident
import java.io.File

/**
 * Replace file:/some/path with file:///some/path. Both are legal formats but IDEA will make the second format
 * clickable when it appears in the test output window.
 */
fun File.toStringWithClickableURI(): String = "$this (${toURI().toString().replaceFirst("/", "///")})"

val DEFAULT_COMMITTER: Ident = Ident("Frank Grimes", "grimey@springfield.example.com")
