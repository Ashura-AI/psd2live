package io.github.psd2live.core

import kotlin.math.sqrt

/**
 * How a deform brush's pull fades from its core to its rim, the same eight profiles Blender's
 * proportional editing offers. Hardness still sets the solid core; the profile shapes what lies past it.
 */
enum class BrushFalloff(val labelKey: String) {
    SMOOTH("editor.falloff.smooth"),
    SPHERE("editor.falloff.sphere"),
    ROOT("editor.falloff.root"),
    INVERSE_SQUARE("editor.falloff.inverseSquare"),
    SHARP("editor.falloff.sharp"),
    LINEAR("editor.falloff.linear"),
    CONSTANT("editor.falloff.constant"),
    RANDOM("editor.falloff.random");

    /** The weight at [t], 1 at the core's edge and 0 at the rim; [seed] picks a point's share under [RANDOM]. */
    fun weight(t: Float, seed: Int = 0): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            SMOOTH -> x * x * (3f - 2f * x)
            SPHERE -> sqrt(x * (2f - x))
            ROOT -> sqrt(x)
            INVERSE_SQUARE -> x * (2f - x)
            SHARP -> x * x
            LINEAR -> x
            CONSTANT -> if (x > 0f) 1f else 0f
            RANDOM -> x * pointNoise(seed)
        }
    }
}
