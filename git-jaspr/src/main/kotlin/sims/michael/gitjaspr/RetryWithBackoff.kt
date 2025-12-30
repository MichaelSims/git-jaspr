package sims.michael.gitjaspr

import org.slf4j.Logger

object RetryWithBackoff {
    fun <T : Any> retryWithBackoff(
        logger: Logger,
        maxAttempts: Int = 5,
        shouldRetry: (Exception) -> Boolean = { true },
        block: () -> T,
    ): T {
        require(maxAttempts >= 1)
        var delay = 250L
        var attemptsMade = 0
        while (true) {
            try {
                return block().also {
                    attemptsMade++
                    if (attemptsMade > 1) {
                        logger.info(
                            "Operation succeeded on attempt {}/{}",
                            attemptsMade,
                            maxAttempts,
                        )
                    }
                }
            } catch (e: Exception) {
                attemptsMade++
                if (!shouldRetry(e) || attemptsMade == maxAttempts) {
                    throw e
                } else {
                    logger.warn(
                        "Operation failed on attempt {}/{}, retrying in {}ms (Error: {})",
                        attemptsMade,
                        maxAttempts,
                        delay,
                        e.message,
                    )
                    Thread.sleep(delay)
                    delay *= 2
                }
            }
        }
    }
}
