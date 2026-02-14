package sims.michael.gitjaspr

import org.slf4j.LoggerFactory

/**
 * Abstraction for displaying messages to the user. Separates user-facing output from diagnostic
 * logging, allowing themed console output alongside telemetry file logging.
 */
interface Renderer {
    fun info(message: String)

    fun warn(message: String)

    fun error(message: String)
}

/**
 * Writes themed messages to stdout/stderr and records them in a dedicated SLF4J logger for file
 * telemetry. The dedicated logger (`sims.michael.gitjaspr.UserOutput`) should be configured with
 * `additivity = false` and only a FILE appender so that messages are not duplicated on the console.
 */
class ConsoleRenderer(private val theme: Theme) : Renderer {
    private val fileLogger = LoggerFactory.getLogger(FILE_LOGGER_NAME)

    override fun info(message: String) {
        println(theme.value(message))
        fileLogger.info(message)
    }

    override fun warn(message: String) {
        println(theme.warning(message))
        fileLogger.warn(message)
    }

    override fun error(message: String) {
        System.err.println(theme.error(message))
        fileLogger.error(message)
    }

    companion object {
        const val FILE_LOGGER_NAME = "sims.michael.gitjaspr.UserOutput"
    }
}

/** Silent renderer. Useful as a default parameter or in tests that don't care about output. */
object NoOpRenderer : Renderer {
    override fun info(message: String) {}

    override fun warn(message: String) {}

    override fun error(message: String) {}
}
