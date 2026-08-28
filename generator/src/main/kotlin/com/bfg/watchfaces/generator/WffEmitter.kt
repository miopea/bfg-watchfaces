package com.bfg.watchfaces.generator

/**
 * Emits the WFF definition for a set of params.
 *
 * Hard-won details, each of which cost a debugging session:
 *
 *  - Colours are #AARRGGBB. Eight digits, alpha FIRST. Six-digit colours are
 *    silently wrong, not rejected.
 *  - Ambient is per-element <Variant mode="AMBIENT" target="alpha" .../>,
 *    NOT a second Scene. Each element carries its interactive value as an
 *    attribute and its ambient value as a child.
 *  - DefaultProviderPolicy takes defaultSystemProvider / defaultSystemProviderType.
 *    NOT systemProvider / defaultType. Both compile, link, sign and install;
 *    the face then never appears in the carousel.
 *  - BoundingArc needs centerX/centerY. Rectangular slots want BoundingBox.
 *
 * WffSchemaTest validates the output of this class against Google's official
 * XSD on every build. Do not skip that test.
 */
object WffEmitter {

    private fun argb(rgb: String, alpha: Int = 255): String =
        "#%02x%s".format(alpha, rgb.removePrefix("#").lowercase())

    fun emit(p: DialParams, faceName: String = "Untitled"): String {
        // There is no default face. The name comes from whoever designs it and
        // becomes the carousel label; "Untitled" is a placeholder for callers
        // that have not been given one, not a product name.
        val l = p.layout
        val ink = argb(p.inkColor)
        val inkDim = argb(p.inkColor, 160)

        // Five positioned slots. TOP and BOTTOM are centred singles; LEFT,
        // MIDDLE and RIGHT share a row and re-centre among themselves, so
        // turning one off closes the gap instead of leaving a hole.
        //
        // A slot set to NONE is not emitted at all. An empty slot still costs a
        // tap target and a frame budget on the watch.
        fun slotXml(source: ComplicationSource, id: Int, x: Int, y: Int, ambientAlpha: Int): String {
            val w = (l.complicationSize * 4.7).toInt()
            val h = (l.complicationSize * 4.0).toInt()
            val iconW = (l.complicationSize * 1.5).toInt()
            return """
    <ComplicationSlot slotId="$id" x="$x" y="$y" width="$w" height="$h"
                      displayName="@string/slot_${source.name.lowercase()}"
                      supportedTypes="SHORT_TEXT MONOCHROMATIC_IMAGE EMPTY" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="$ambientAlpha"/>
      <DefaultProviderPolicy defaultSystemProvider="${source.wff}" defaultSystemProviderType="SHORT_TEXT"/>
      <BoundingBox x="0" y="0" width="$w" height="$h" outlinePadding="2.0"/>
      <Complication type="SHORT_TEXT">
        <PartImage x="${(w - iconW) / 2}" y="0" width="$iconW" height="${(l.complicationSize * 1.25).toInt()}">
          <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]"/>
        </PartImage>
        <PartText x="0" y="${(l.complicationSize * 1.4).toInt()}" width="$w" height="${(l.complicationSize * 1.8).toInt()}">
          <Text align="CENTER">
            <Font family="${l.fontFamily}" size="${(l.complicationSize * 0.92).toInt()}" color="$ink">
              <Template><![CDATA[%s]]><Parameter expression="[COMPLICATION.TEXT]"/></Template>
            </Font>
          </Text>
        </PartText>
      </Complication>
    </ComplicationSlot>"""
        }

        val w = (l.complicationSize * 4.7).toInt()
        val slotList = ArrayList<String>()
        var nextId = 0

        // TOP keeps a dim ambient variant. It is the always-readable position --
        // it held the date before it became a slot, and DECISIONS.md records the
        // ambient design as deliberately keeping that line visible at alpha 140.
        p.slot(SlotPosition.TOP).let { src ->
            if (src.enabled) slotList += slotXml(src, nextId++, DIAL_SIZE / 2 - w / 2,
                l.dateY - (l.complicationSize * 1.2).toInt(), 140)
        }

        val row = listOf(SlotPosition.LEFT, SlotPosition.MIDDLE, SlotPosition.RIGHT)
            .map { p.slot(it) }.filter { it.enabled }
        row.forEachIndexed { index, src ->
            val offset = (index - (row.size - 1) / 2.0) * l.complicationSpread
            slotList += slotXml(src, nextId++, (DIAL_SIZE / 2 + offset - w / 2).toInt(),
                l.complicationY - (l.complicationSize * 1.2).toInt(), 0)
        }

        p.slot(SlotPosition.BOTTOM).let { src ->
            if (src.enabled) slotList += slotXml(src, nextId++, DIAL_SIZE / 2 - w / 2,
                l.batteryY - (l.complicationSize * 1.2).toInt(), 0)
        }

        val slots = slotList.joinToString("\n")

        // The emitted file is what ships, and since the workbench bakes it into
        // watchface-template it replaces what used to be a hand-annotated
        // reference. Carry that file's hard-won notes into the output so the
        // knowledge lives with the artefact instead of being overwritten by it.
        return """<?xml version="1.0" encoding="utf-8"?>
<!--
  $faceName - Watch Face Format definition.
  Generated by the BFG Watch Faces generator, v${p.generatorVersion}. Do not hand-edit:
  re-bake it instead, with ./gradlew :workbench:bake

  Canvas is 456x456: correct for Pixel Watch 4 and 5, both case sizes.
  Colours are AARRGGBB (8 digits, alpha FIRST). Six-digit values are silently
  wrong, not rejected.

  Ambient is handled by per-element Variant mode="AMBIENT", NOT by a second
  scene. Each element declares its interactive value as an attribute and its
  ambient value as a child.

  NOTE: the package in AndroidManifest.xml follows the Watch Face Push naming
  requirement, <app package>.watchfacepush.<face slug>. Push rejects anything
  else, and it lives in the binary manifest, so only pack can vary it at runtime.
-->
<WatchFace width="$DIAL_SIZE" height="$DIAL_SIZE">
  <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
  <Metadata key="PREVIEW_TIME" value="10:10:00"/>

  <Scene backgroundColor="#ff000000">

    <PartImage x="0" y="0" width="$DIAL_SIZE" height="$DIAL_SIZE" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      <Image resource="dial_bg"/>
    </PartImage>

    <DigitalClock x="0" y="${l.timeY - l.timeSize / 2}" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}">
      <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER"
                x="0" y="0" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}" alpha="255">
        <Variant mode="AMBIENT" target="alpha" value="0"/>
        <Font family="${l.fontFamily}" size="${l.timeSize}" weight="${l.fontWeight}" color="$ink"/>
      </TimeText>
      <TimeText format="hh:mm" hourFormat="SYNC_TO_DEVICE" align="CENTER"
                x="0" y="0" width="$DIAL_SIZE" height="${(l.timeSize * 1.4).toInt()}" alpha="0">
        <Variant mode="AMBIENT" target="alpha" value="255"/>
        <Font family="${l.fontFamily}" size="${l.timeSize}" weight="THIN" color="$inkDim"/>
      </TimeText>
    </DigitalClock>
$slots

  </Scene>
</WatchFace>
"""
    }

    /**
     * Watch Face Push requires: <app package>.watchfacepush.<face name>
     * The API rejects anything else. Package name lives in the binary manifest,
     * so pack must generate it - it cannot be swapped into a prebuilt APK.
     */
    fun pushPackageName(appPackage: String, faceSlug: String): String {
        require(faceSlug.matches(Regex("^[a-z][a-z0-9_]*$"))) {
            "face slug must be lowercase alphanumeric/underscore, got '$faceSlug'"
        }
        return "$appPackage.watchfacepush.$faceSlug"
    }
}
