package com.bfg.watchfaces.workbench

import com.bfg.watchfaces.generator.CURRENT_GENERATOR_VERSION
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.Layout

/**
 * Query-string <-> [DialParams], plus JSON emission.
 *
 * Hand-rolled rather than pulled from a JSON library on purpose: :generator has
 * no dependencies and the workbench is a dev tool that should not be the reason
 * the project acquires a serialization framework. When faces become a shared
 * catalog format, that decision gets made deliberately, in :generator, with a
 * schema -- not incidentally here.
 */
object ParamCodec {

    fun fromQuery(q: Map<String, String>): DialParams {
        val d = DialParams()
        val l = d.layout
        return DialParams(
            generatorVersion = q.int("generatorVersion", CURRENT_GENERATOR_VERSION),
            engine = q["engine"]?.uppercase()?.let { runCatching { Engine.valueOf(it) }.getOrNull() } ?: d.engine,
            scale = q.dbl("scale", d.scale),
            depth = q.dbl("depth", d.depth),
            freq = q.int("freq", d.freq),
            stroke = q.dbl("stroke", d.stroke),
            relief = q.dbl("relief", d.relief),
            contrast = q.dbl("contrast", d.contrast),
            rotate = q.dbl("rotate", d.rotate),
            vignette = q.dbl("vignette", d.vignette),
            dialColor = q["dialColor"]?.normalizeHex() ?: d.dialColor,
            inkColor = q["inkColor"]?.normalizeHex() ?: d.inkColor,
            sheen = q.dbl("sheen", d.sheen),
            lens = q["lens"]?.let { it == "true" || it == "1" } ?: d.lens,
            lensAmount = q.dbl("lensAmount", d.lensAmount),
            texture = q["texture"] ?: d.texture,
            complications = Complications.parse(q["complications"]) ?: d.complications,
            layout = Layout(
                dateY = q.int("dateY", l.dateY),
                dateSize = q.int("dateSize", l.dateSize),
                timeY = q.int("timeY", l.timeY),
                timeSize = q.int("timeSize", l.timeSize),
                tracking = q.dbl("tracking", l.tracking),
                complicationY = q.int("complicationY", l.complicationY),
                complicationSpread = q.int("complicationSpread", l.complicationSpread),
                complicationSize = q.int("complicationSize", l.complicationSize),
                batteryY = q.int("batteryY", l.batteryY),
                fontFamily = q["fontFamily"] ?: l.fontFamily,
                fontWeight = q["fontWeight"] ?: l.fontWeight
            )
        )
    }

    /**
     * Reads the `params` object of a stored face.
     *
     * Routes through [fromQuery] rather than duplicating the field list, so a
     * new parameter cannot be supported by the URL and silently dropped by the
     * catalog reader -- which would corrupt a stored face on load with no error.
     * The nested `layout` object is flattened first, matching the query shape.
     */
    fun fromJson(o: Map<String, Any?>): DialParams {
        val flat = LinkedHashMap<String, String>()
        fun put(k: String, v: Any?) {
            when (v) {
                null -> {}
                is Double -> flat[k] = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                else -> flat[k] = v.toString()
            }
        }
        for ((k, v) in o) when {
            k == "layout" -> {}
            // The catalog stores complications as an array; the query form uses
            // a comma-separated list. Flatten here so there is exactly one
            // parser, in fromQuery, rather than two that can disagree.
            k == "complications" && v is List<*> -> flat[k] = v.joinToString(",") { it.toString() }
            else -> put(k, v)
        }
        for ((k, v) in Json.obj(o["layout"])) put(k, v)
        return fromQuery(flat)
    }

    /** `#RGB` and bare `RRGGBB` are accepted here and normalized; DialParams itself is strict. */
    private fun String.normalizeHex(): String {
        val v = trim().removePrefix("#")
        val full = if (v.length == 3) v.map { "$it$it" }.joinToString("") else v
        return "#${full.uppercase()}"
    }

    private fun Map<String, String>.dbl(k: String, def: Double) = this[k]?.toDoubleOrNull() ?: def
    private fun Map<String, String>.int(k: String, def: Int) = this[k]?.toDoubleOrNull()?.toInt() ?: def

    fun toJson(p: DialParams): String {
        val l = p.layout
        return """{
  "generatorVersion": ${p.generatorVersion},
  "engine": "${p.engine}",
  "scale": ${p.scale}, "depth": ${p.depth}, "freq": ${p.freq},
  "stroke": ${p.stroke}, "relief": ${p.relief}, "contrast": ${p.contrast},
  "rotate": ${p.rotate}, "vignette": ${p.vignette}, "sheen": ${p.sheen},
  "dialColor": "${p.dialColor}", "inkColor": "${p.inkColor}",
  "lens": ${p.lens}, "lensAmount": ${p.lensAmount},
  "texture": "${p.texture}",
  "complications": [${p.complications.joinToString(", ") { "\"${it.name}\"" }}],
  "layout": {
    "dateY": ${l.dateY}, "dateSize": ${l.dateSize},
    "timeY": ${l.timeY}, "timeSize": ${l.timeSize}, "tracking": ${l.tracking},
    "complicationY": ${l.complicationY}, "complicationSpread": ${l.complicationSpread},
    "complicationSize": ${l.complicationSize}, "batteryY": ${l.batteryY},
    "fontFamily": "${l.fontFamily}", "fontWeight": "${l.fontWeight}"
  }
}"""
    }

    fun toQuery(p: DialParams): String {
        val l = p.layout
        val kv = linkedMapOf(
            "engine" to p.engine.name, "scale" to p.scale, "depth" to p.depth, "freq" to p.freq,
            "stroke" to p.stroke, "relief" to p.relief, "contrast" to p.contrast,
            "rotate" to p.rotate, "vignette" to p.vignette, "sheen" to p.sheen,
            "dialColor" to p.dialColor, "inkColor" to p.inkColor,
            "lens" to p.lens, "lensAmount" to p.lensAmount, "texture" to p.texture,
            "complications" to Complications.format(p.complications),
            "dateY" to l.dateY, "dateSize" to l.dateSize, "timeY" to l.timeY,
            "timeSize" to l.timeSize, "tracking" to l.tracking,
            "complicationY" to l.complicationY, "complicationSpread" to l.complicationSpread,
            "complicationSize" to l.complicationSize, "batteryY" to l.batteryY,
            "fontFamily" to l.fontFamily, "fontWeight" to l.fontWeight
        )
        return kv.entries.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode(v.toString(), Charsets.UTF_8)
        }
    }

    /**
     * The starting library.
     *
     * No face is "the" face any more. There is no hardcoded default identity in
     * the product: these are starting points, and a face gets its real name when
     * someone saves it (see [FaceStore]). That is what retired "Silver Sand" --
     * naming the shipped face after one colourway was the same category error as
     * naming the app after it, recorded in DECISIONS.md 2026-08-26.
     *
     * Order matters: the first entry is what the app opens on.
     */
    val presets: LinkedHashMap<String, DialParams> = linkedMapOf(
        "Knotwork Taupe" to DialParams(
            engine = Engine.KNOTWORK, scale = 26.0, depth = 3.0, freq = 7, stroke = 1.05,
            relief = 1.5, contrast = 36.0, rotate = 45.0, vignette = 20.0,
            dialColor = "#7D7369", inkColor = "#FCF9F1", sheen = 28.0
        ),
        "Knotwork Graphite" to DialParams(
            engine = Engine.KNOTWORK, scale = 22.0, depth = 2.6, freq = 3, stroke = 1.0,
            relief = 1.4, contrast = 30.0, rotate = 45.0, vignette = 30.0,
            dialColor = "#2B2E33", inkColor = "#ECEAE5", sheen = 16.0
        ),
        "Brushed Steel" to DialParams(
            engine = Engine.BRUSHED, scale = 20.0, contrast = 34.0, relief = 2.2,
            rotate = 20.0, vignette = 22.0, sheen = 24.0,
            dialColor = "#6E7378", inkColor = "#FCFCFA"
        ),
        "Carbon Black" to DialParams(
            engine = Engine.CARBON, scale = 16.0, contrast = 30.0, relief = 1.8,
            rotate = 45.0, vignette = 30.0, sheen = 12.0,
            dialColor = "#26282B", inkColor = "#E8E6E1"
        ),
        "Botanical Sand" to DialParams(engine = Engine.BOTANICAL),
        "Clous de Paris" to DialParams(
            engine = Engine.CLOUS, scale = 22.0, depth = 4.0, contrast = 34.0,
            dialColor = "#6E6A66", inkColor = "#F5F2EC", rotate = 45.0, vignette = 22.0
        ),
        "Rosette Noir" to DialParams(
            engine = Engine.ROSETTE, scale = 16.0, depth = 7.0, freq = 11, contrast = 26.0,
            dialColor = "#23262B", inkColor = "#E8E6E1", sheen = 18.0, vignette = 30.0
        ),
        "Barleycorn Brass" to DialParams(
            engine = Engine.BARLEYCORN, scale = 26.0, depth = 5.5, freq = 5, contrast = 32.0,
            dialColor = "#8A7343", inkColor = "#FDF8E9", sheen = 38.0, rotate = 20.0
        ),
        "Sunburst Ice" to DialParams(
            engine = Engine.SUNBURST, scale = 9.0, depth = 6.0, freq = 3, contrast = 22.0,
            dialColor = "#5A6B77", inkColor = "#FFFFFF", sheen = 44.0, vignette = 26.0
        ),
        "Flat" to DialParams(engine = Engine.NONE, sheen = 22.0, vignette = 20.0)
    )
}
