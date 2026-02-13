package sims.michael.gitjaspr

import com.github.ajalt.clikt.core.PrintMessage
import java.io.File

/**
 * Generates bash, zsh, and fish completion scripts by invoking Clikt's `--generate-completion`
 * option. Uses [com.github.ajalt.clikt.core.CliktCommand.parse] instead of
 * [com.github.ajalt.clikt.core.CliktCommand.main] to avoid the `System.exit` call, allowing all
 * three shells to be generated in one process.
 *
 * NOTE: Newer versions of Clikt have better support for completions and would allow us to avoid
 * having to catch PrintMessage below.
 *
 * Must be run from within a git repository.
 */
object GenerateCompletions {

    @JvmStatic
    fun main(args: Array<String>) {
        val shells = mapOf("bash" to "jaspr.bash", "zsh" to "_jaspr", "fish" to "jaspr.fish")
        val outputDir = File(args.firstOrNull() ?: "build/completions")
        outputDir.mkdirs()
        for ((shell, filename) in shells) {
            val script =
                try {
                    buildCommand().parse(listOf("--generate-completion=$shell"))
                    error("Expected PrintMessage from --generate-completion")
                } catch (e: PrintMessage) {
                    e.message.orEmpty()
                }
            val file = outputDir.resolve(filename)
            file.writeText(script)
            println("Generated $file")
        }
    }
}
