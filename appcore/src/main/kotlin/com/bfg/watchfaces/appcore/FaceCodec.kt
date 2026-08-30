package com.bfg.watchfaces.appcore

import com.bfg.watchfaces.generator.CURRENT_GENERATOR_VERSION
import com.bfg.watchfaces.generator.DateStyle
import com.bfg.watchfaces.generator.DialParams
import com.bfg.watchfaces.generator.Engine
import com.bfg.watchfaces.generator.Layout

/**
 * Query-string <-> [DialParams], plus the JSON both shipped apps store.
 *
 * This lives in :appcore rather than :workbench because a face saved on the
 * PHONE has to be byte-compatible with one saved by the workbench and with a
 * catalog submission -- docs/SPEC.md makes the saved file and the submission the
 * same shape, so there is no export step to write later. Two codecs would be two
 * chances to disagree about a face somebody already owns.
 *
 * Hand-rolled rather than pulled from a JSON library on purpose: :generator has
 * no dependencies, and a serialization framework is not something to acquire
 * incidentally. When the catalog gets a schema, that decision gets made
 * deliberately.
 */
object FaceCodec {

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
            showSeconds = q["showSeconds"]?.let { it == "true" || it == "1" } ?: d.showSeconds,
            showComplicationIcons = q["showComplicationIcons"]?.let { it == "true" || it == "1" }
                ?: d.showComplicationIcons,
            // An unknown name falls back to the default rather than throwing: a
            // face saved by a newer build must still open here, minus whatever
            // this build does not understand.
            dateStyle = q["dateStyle"]?.let { name ->
                DateStyle.entries.firstOrNull { it.name == name }
            } ?: d.dateStyle,
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
  "showSeconds": ${p.showSeconds}, "showComplicationIcons": ${p.showComplicationIcons},
  "dateStyle": "${p.dateStyle}",
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
            // FIRST, and not optional. fromQuery defaults a missing
            // generatorVersion to CURRENT_GENERATOR_VERSION, so a query form
            // that omits it silently upgrades an old face to today's geometry
            // -- which is the one thing DECISIONS.md says must never happen
            // implicitly. The workbench round-trips saved faces through this
            // form to list them, and `bake --preset` merges through it.
            "generatorVersion" to p.generatorVersion,
            "engine" to p.engine.name, "scale" to p.scale, "depth" to p.depth, "freq" to p.freq,
            "stroke" to p.stroke, "relief" to p.relief, "contrast" to p.contrast,
            "rotate" to p.rotate, "vignette" to p.vignette, "sheen" to p.sheen,
            "dialColor" to p.dialColor, "inkColor" to p.inkColor,
            "showSeconds" to p.showSeconds,
            "showComplicationIcons" to p.showComplicationIcons,
            "dateStyle" to p.dateStyle.name,
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
}
