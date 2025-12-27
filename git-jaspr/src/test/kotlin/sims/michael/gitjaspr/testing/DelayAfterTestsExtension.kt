package sims.michael.gitjaspr.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory

/**
 * JUnit extension that introduces a delay after each test (useful to avoid hitting API rate
 * limits). The default delay is 1000 ms, but it can be customized per-class using the
 * [DelayAfterTestMillis] annotation.
 */
class DelayAfterTestsExtension : AfterEachCallback {
    private val logger = LoggerFactory.getLogger(DelayAfterTestsExtension::class.java)

    override fun afterEach(context: ExtensionContext) {
        val delayMillis =
            context.requiredTestClass.getAnnotation(DelayAfterTestMillis::class.java)?.millis
                ?: DEFAULT_DELAY_MILLIS
        logger.debug("Delaying for {} ms after test...", delayMillis)
        Thread.sleep(delayMillis)
        logger.debug("Delay complete")
    }

    companion object {
        const val DEFAULT_DELAY_MILLIS: Long = 1000L
    }
}
