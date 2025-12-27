package sims.michael.gitjaspr.testing

/**
 * Specifies a custom delay (in milliseconds) to wait after each test in a class completes. Used in
 * conjunction with [DelayAfterTestsExtension].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DelayAfterTestMillis(val millis: Long)
