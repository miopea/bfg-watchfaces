package com.bfg.watchfaces.generator

/**
 * A point in dial space. Dial space is always 456x456 with the origin at the
 * top-left, matching the WFF canvas. Renderers scale from here.
 */
data class Pt(val x: Double, val y: Double)

/** An open polyline. Engines emit these; renderers stroke them. */
typealias Polyline = List<Pt>

enum class Engine { LATTICE, CLOUS, ROSETTE, BARLEYCORN, SUNBURST, BOTANICAL, KNOTWORK, NONE }

/**
 * Everything needed to reproduce a dial.
 *
 * IMPORTANT - [generatorVersion] is load-bearing.
 *
 * Community faces are distributed as these parameters, not as rendered images.
 * That means this class IS the file format, and the engine code IS the renderer
 * for that format. If an engine's output changes, every face pinned to the old
 * version renders differently than its author intended.
 *
 * So: never change an engine's geometry in place. Add a new branch keyed on
 * [generatorVersion] and leave the old path alone. See [PatternEngines.paths].
 * GeneratorVersionTest fails if CURRENT changes without golden updates.
 */
data class DialParams(
    val generatorVersion: Int = CURRENT_GENERATOR_VERSION,

    val engine: Engine = Engine.BOTANICAL,
    val scale: Double = 40.0,
    val depth: Double = 5.0,
    val freq: Int = 7,
    val stroke: Double = 1.2,
    val relief: Double = 1.4,
    val contrast: Double = 30.0,
    val rotate: Double = 45.0,
    val vignette: Double = 18.0,

    val dialColor: String = "#7D7369",
    val inkColor: String = "#FCF9F1",
    val sheen: Double = 30.0,

    /** Draw the pattern OVER the numerals rather than behind them. */
    val lens: Boolean = true,
    val lensAmount: Double = 38.0,

    val layout: Layout = Layout()
) {
    init {
        require(generatorVersion in 1..CURRENT_GENERATOR_VERSION) {
            "unknown generatorVersion=$generatorVersion (this build supports up to $CURRENT_GENERATOR_VERSION)"
        }
        require(scale >= 4.0) { "scale must be >= 4" }
        require(HEX.matches(dialColor)) { "dialColor must be #RRGGBB, got $dialColor" }
        require(HEX.matches(inkColor)) { "inkColor must be #RRGGBB, got $inkColor" }
    }

    companion object {
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    }
}

data class Layout(
    val dateY: Int = 118,
    val dateSize: Int = 21,
    val timeY: Int = 196,
    val timeSize: Int = 104,
    val tracking: Double = 0.0,
    val complicationY: Int = 286,
    val complicationSpread: Int = 86,
    val complicationSize: Int = 19,
    val batteryY: Int = 348,
    val fontFamily: String = "SYNC_TO_DEVICE",
    val fontWeight: String = "MEDIUM"
)

/**
 * Bump ONLY when adding an engine or a parameter. Never when changing geometry.
 *
 * v2 (2026-08-27) added [Engine.KNOTWORK]. Every other engine is UNCHANGED --
 * PatternEngines.v2 delegates to v1 for them rather than copying the code, so
 * they cannot drift. A face stored with generatorVersion=1 still renders through
 * the v1 branch, byte for byte.
 */
const val CURRENT_GENERATOR_VERSION = 2

/** WFF canvas. Correct for Pixel Watch 4 and 5, both case sizes. */
const val DIAL_SIZE = 456
const val DIAL_RADIUS = DIAL_SIZE / 2.0
const val DIAL_CENTER = DIAL_SIZE / 2.0
