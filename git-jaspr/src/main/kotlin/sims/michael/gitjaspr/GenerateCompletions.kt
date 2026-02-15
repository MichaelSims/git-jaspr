package sims.michael.gitjaspr

import com.github.ajalt.clikt.completion.CompletionGenerator
import java.io.File

/**
 * Generates bash, zsh, and fish completion scripts using Clikt's [CompletionGenerator].
 *
 * Must be run from within a git repository.
 */
object GenerateCompletions {

    @JvmStatic
    fun main(args: Array<String>) {
        val shells = mapOf("bash" to "jaspr.bash", "zsh" to "_jaspr", "fish" to "jaspr.fish")
        val outputDir = File(args.firstOrNull() ?: "build/completions")
        outputDir.mkdirs()
        val command = buildCommand().also { it.resetContext() }
        for ((shell, filename) in shells) {
            var script = CompletionGenerator.generateCompletionForCommand(command, shell)
            // Clikt's zsh output uses bashcompinit but omits the #compdef directive that
            // zsh's compinit needs to autoload the file from site-functions.
            if (shell == "zsh") script = "#compdef jaspr\n\n$script"
            val file = outputDir.resolve(filename)
            file.writeText(script)
            println("Generated $file")
        }
    }
}
