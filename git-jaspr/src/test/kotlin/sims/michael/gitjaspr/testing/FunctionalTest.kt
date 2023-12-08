package sims.michael.gitjaspr.testing

import org.junit.jupiter.api.Tag

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("functional")
annotation class FunctionalTest
