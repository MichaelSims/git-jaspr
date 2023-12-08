package sims.michael.gitjaspr.serde

import ch.qos.logback.classic.Level
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class LevelSerializer : KSerializer<Level> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        checkNotNull(Level::class.simpleName),
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: Level) {
        encoder.encodeString(value.levelStr)
    }

    override fun deserialize(decoder: Decoder): Level {
        return Level.toLevel(decoder.decodeString())
    }
}
