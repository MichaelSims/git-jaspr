package sims.michael.gitjaspr

import org.slf4j.LoggerFactory

/**
 * Abstraction for displaying messages to the user. Separates user-facing output from diagnostic
 * logging, allowing themed console output alongside telemetry file logging.
 */
interface Renderer {
    fun info(message: Theme.() -> String)

    fun warn(message: Theme.() -> String)

    fun error(message: Theme.() -> String)
}

/**
 * Writes themed messages to stdout/stderr and records them in a dedicated SLF4J logger for file
 * telemetry. The dedicated logger (`sims.michael.gitjaspr.UserOutput`) should be configured with
 * `additivity = false` and only a FILE appender so that messages are not duplicated on the console.
 */
class ConsoleRenderer(private val theme: Theme) : Renderer {
    private val fileLogger = LoggerFactory.getLogger(FILE_LOGGER_NAME)

    override fun info(message: Theme.() -> String) {
        println(theme.message())
        fileLogger.info(MonoTheme.message())
    }

    override fun warn(message: Theme.() -> String) {
        println(theme.warning(theme.message()))
        fileLogger.warn(MonoTheme.message())
    }

    override fun error(message: Theme.() -> String) {
        System.err.println(theme.error(theme.message()))
        fileLogger.error(MonoTheme.message())
    }

    companion object {
        const val FILE_LOGGER_NAME = "sims.michael.gitjaspr.UserOutput"
    }
}

/** Silent renderer. Useful as a default parameter or in tests that don't care about output. */
object NoOpRenderer : Renderer {
    override fun info(message: Theme.() -> String) {}

    override fun warn(message: Theme.() -> String) {}

    override fun error(message: Theme.() -> String) {}
}
