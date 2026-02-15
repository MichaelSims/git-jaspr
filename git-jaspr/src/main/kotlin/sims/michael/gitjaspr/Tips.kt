package sims.michael.gitjaspr

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Parses tips from the `tips.md` resource file — lines starting with `- ` are tips. */
fun loadTipsFromResource(): List<String> =
    checkNotNull(TipProvider::class.java.getResourceAsStream("/tips.md")) {
            "tips.md resource not found"
        }
        .bufferedReader()
        .readLines()
        .filter { it.startsWith("- ") }
        .map { it.removePrefix("- ") }

private const val TIP_INTERVAL = 5

@Serializable
private data class TipsState(
    val seenIndices: Set<Int> = emptySet(),
    val invocationsSinceLastTip: Int = TIP_INTERVAL,
)

/** Manages tip selection and state persistence. */
class TipProvider(
    private val stateFile: File =
        File(System.getProperty("java.io.tmpdir")).resolve("jaspr/tips-state.json"),
    private val tips: List<String> = loadTipsFromResource(),
) {
    private val logger = LoggerFactory.getLogger(TipProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        logger.info("Using state file {}", stateFile.absolutePath)
    }

    /**
     * Returns a tip if it's time to show one, or null otherwise. Updates state as a side effect.
     */
    fun getNextTip(): String? {
        logger.trace("getNextTip")
        if (tips.isEmpty()) return null

        val state = loadState()
        logger.trace("getNextTip state loaded: {}", state)
        val nextCount = state.invocationsSinceLastTip + 1

        if (nextCount < TIP_INTERVAL) {
            logger.trace("Not time to show tip yet, invocationsSinceLastTip: {}", nextCount)
            saveState(state.copy(invocationsSinceLastTip = nextCount))
            return null
        }

        // Time to show a tip — pick a random unseen one
        val seenIndices = if (state.seenIndices.size >= tips.size) emptySet() else state.seenIndices
        val unseenIndices = tips.indices.filter { it !in seenIndices }
        val chosenIndex = unseenIndices.random()

        saveState(TipsState(seenIndices = seenIndices + chosenIndex, invocationsSinceLastTip = 0))
        return tips[chosenIndex]
    }

    private fun loadState(): TipsState =
        try {
            if (stateFile.exists()) json.decodeFromString(stateFile.readText()) else TipsState()
        } catch (e: Exception) {
            logger.debug("Could not load tips state, resetting", e)
            TipsState()
        }

    private fun saveState(state: TipsState) {
        logger.trace("Saving tips state: {}", state)
        try {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        } catch (e: Exception) {
            logger.debug("Could not save tips state", e)
        }
    }
}
