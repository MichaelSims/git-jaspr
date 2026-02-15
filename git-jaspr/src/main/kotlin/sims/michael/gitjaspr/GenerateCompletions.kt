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
            var finalScript = script
            if (shell == "bash" || shell == "zsh") {
                // Clikt wraps Custom completion functions with `compgen -F` which doesn't
                // work: the function sets COMPREPLY directly using COMP_WORDS/COMP_CWORD,
                // but compgen -F (without a word argument) fails silently. Replace with a
                // direct function call which works correctly in the completion context.
                finalScript =
                    finalScript.replace(
                        Regex("""COMPREPLY=\(\$\(compgen -F (\S+) 2>/dev/null\)\)"""),
                        "$1",
                    )
            }
            // Clikt's zsh output uses bashcompinit but omits the #compdef directive that
            // zsh's compinit needs to auto-load the file from site-functions.
            if (shell == "zsh") finalScript = "#compdef jaspr\n\n$finalScript"
            file.writeText(finalScript)
            println("Generated $file")
        }
    }
}
