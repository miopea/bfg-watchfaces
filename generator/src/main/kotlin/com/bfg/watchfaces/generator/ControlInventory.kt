package com.bfg.watchfaces.generator

/**
 * Every control a person can move, and the range it moves in.
 *
 * ## Why this is here and not in each UI
 *
 * The localhost app and `:mobile` are two front ends onto one file format. Until
 * now each one listed the controls itself, and a test checked the lists matched.
 * That is the shape this repo keeps getting hurt by: `SlotGeometry` exists
 * because the slot arithmetic was written twice with a test asserting the copies
 * agreed, and they agreed while both were wrong. A test that two copies match
 * cannot tell you they are both correct.
 *
 * So the controls live once, here, and both UIs build themselves from this. They
 * cannot disagree, rather than being checked for disagreement.
 *
 * ## What is deliberately NOT here
 *
 * **Labels.** "Pattern size", "Engraving", "Edge shading" are presentation, and
 * [DialParams] already says so: "Presentation (labels, sample values for a
 * preview) deliberately lives in the workbench, not here." A watch app and a
 * phone app may well want different words on a smaller screen, and dragging the
 * copy in here to make a test easier would be inventing a constraint nobody
 * asked for.
 *
 * **The starting design.** The app opens on a Knotwork face, which is a curated
 * starting point and NOT [DialParams]' bare defaults. That distinction is easy
 * to erase by accident and would silently change the first thing anyone sees, so
 * it stays where it is: in the UI, as a preset.
 *
 * ## The ranges are the part that did not exist anywhere
 *
 * `min`/`max`/`step` lived only in the HTML, which meant `:generator` had no
 * idea what values a user could actually reach. A range wider than the geometry
 * tolerates was invisible until somebody dragged a slider into it. Now every
 * reachable value is enumerable, and `ControlInventoryTest` walks them and
 * asserts each one still emits a schema-valid face.
 */
object ControlInventory {

    /** Which structure the control writes to. */
    enum class Target {
        /** A field on [DialParams] itself — the pattern's own shape. */
        PATTERN,

        /** A field on [Layout] — where things sit on the dial. */
        LAYOUT
    }

    /**
     * One slider.
     *
     * [id] is the name used on the wire and in stored faces, so it is the same
     * string a UI sends back. [integral] marks the ones stored as `Int`, which a
     * UI must round before sending — a fractional `freq` is not a finer setting,
     * it is a parse failure waiting to happen.
     */
    data class Control(
        val id: String,
        val min: Double,
        val max: Double,
        val step: Double,
        val target: Target,
        val integral: Boolean = false
    ) {
        /** Every value this control can actually take, in order. */
        fun values(): List<Double> {
            val out = ArrayList<Double>()
            var v = min
            while (v <= max + 1e-9) {
                out.add(if (integral) Math.round(v).toDouble() else v)
                v += step
            }
            return out.distinct()
        }
    }

    /**
     * In the order a UI should show them: the pattern first, then the layout.
     *
     * Order is part of the inventory rather than each UI's own decision, so the
     * two front ends present the same thing in the same sequence and a person
     * moving between them is not hunting for a slider that moved.
     */
    val CONTROLS: List<Control> = listOf(
        Control("scale", 4.0, 80.0, 0.5, Target.PATTERN),
        Control("depth", 0.0, 20.0, 0.1, Target.PATTERN),
        Control("freq", 1.0, 24.0, 1.0, Target.PATTERN, integral = true),
        Control("stroke", 0.2, 4.0, 0.05, Target.PATTERN),
        Control("relief", 0.0, 6.0, 0.1, Target.PATTERN),
        Control("rotate", 0.0, 360.0, 1.0, Target.PATTERN),
        Control("contrast", 0.0, 100.0, 1.0, Target.PATTERN),
        Control("sheen", 0.0, 100.0, 1.0, Target.PATTERN),
        Control("vignette", 0.0, 100.0, 1.0, Target.PATTERN),

        Control("timeSize", 40.0, 170.0, 1.0, Target.LAYOUT, integral = true),
        // 300, not 380. Measured: with all five complication slots on, every
        // timeY from 304 upward makes slots overlap — a quarter of the old
        // slider produced a visibly broken face. SlotGeometry cannot rescue it
        // either: the clock that low leaves nowhere for a row to go, and it
        // already shrinks slots as far as MIN_SIZE before giving up.
        //
        // The bound is the worst case (five slots). A face with none could sit
        // the clock lower, but a range that depends on how many complications
        // are switched on is a slider that moves under the user's finger.
        // 300 keeps a small margin under the measured 303.
        Control("timeY", 80.0, 300.0, 1.0, Target.LAYOUT, integral = true),
        Control("complicationSpread", 60.0, 150.0, 1.0, Target.LAYOUT, integral = true),
        Control("complicationY", 200.0, 360.0, 1.0, Target.LAYOUT, integral = true),
        Control("dateY", 40.0, 160.0, 1.0, Target.LAYOUT, integral = true),
        Control("batteryY", 250.0, 430.0, 1.0, Target.LAYOUT, integral = true)
    )

    fun byId(id: String): Control? = CONTROLS.firstOrNull { it.id == id }

    /**
     * Round a raw value onto the control's own grid, and into its range.
     *
     * A continuous slider hands back whatever fraction the finger landed on.
     * Storing that unrounded is how a `freq` of 6.9997 gets written, and
     * `freq` is an Int — so the next read is 6 and the slider jumps backwards
     * under the finger. Rounding here rather than in each front end keeps the
     * grid in the same place as the [Control.step] that defines it.
     */
    fun snap(control: Control, value: Double): Double {
        val steps = Math.round((value - control.min) / control.step).toDouble()
        val snapped = control.min + steps * control.step
        val clamped = snapped.coerceIn(control.min, control.max)
        return if (control.integral) Math.round(clamped).toDouble() else clamped
    }

    /**
     * Apply one control's value to a set of parameters.
     *
     * Here rather than in each UI because it is the other half of the same
     * contract: knowing a control is called "relief" is useless to a front end
     * that then has to hand-write which field that is.
     */
    fun with(p: DialParams, id: String, value: Double): DialParams = when (id) {
        "scale" -> p.copy(scale = value)
        "depth" -> p.copy(depth = value)
        "freq" -> p.copy(freq = value.toInt())
        "stroke" -> p.copy(stroke = value)
        "relief" -> p.copy(relief = value)
        "rotate" -> p.copy(rotate = value)
        "contrast" -> p.copy(contrast = value)
        "sheen" -> p.copy(sheen = value)
        "vignette" -> p.copy(vignette = value)

        "timeSize" -> p.copy(layout = p.layout.copy(timeSize = value.toInt()))
        "timeY" -> p.copy(layout = p.layout.copy(timeY = value.toInt()))
        "complicationSpread" -> p.copy(layout = p.layout.copy(complicationSpread = value.toInt()))
        "complicationY" -> p.copy(layout = p.layout.copy(complicationY = value.toInt()))
        "dateY" -> p.copy(layout = p.layout.copy(dateY = value.toInt()))
        "batteryY" -> p.copy(layout = p.layout.copy(batteryY = value.toInt()))

        else -> throw IllegalArgumentException("no control called '$id'")
    }

    /**
     * Read one control's current value out of a set of parameters.
     *
     * The other half of [with], and here for the same reason. A UI that builds
     * its sliders from [CONTROLS] has to position each one at where the face
     * actually is, and without this it would need its own hand-written map from
     * control id to field — which is exactly the duplicated list this object
     * exists to delete. Two of them would drift in opposite directions.
     */
    fun valueOf(p: DialParams, id: String): Double = when (id) {
        "scale" -> p.scale
        "depth" -> p.depth
        "freq" -> p.freq.toDouble()
        "stroke" -> p.stroke
        "relief" -> p.relief
        "rotate" -> p.rotate
        "contrast" -> p.contrast
        "sheen" -> p.sheen
        "vignette" -> p.vignette

        "timeSize" -> p.layout.timeSize.toDouble()
        "timeY" -> p.layout.timeY.toDouble()
        "complicationSpread" -> p.layout.complicationSpread.toDouble()
        "complicationY" -> p.layout.complicationY.toDouble()
        "dateY" -> p.layout.dateY.toDouble()
        "batteryY" -> p.layout.batteryY.toDouble()

        else -> throw IllegalArgumentException("no control called '$id'")
    }
}
