package com.bfg.watchfaces.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The four bottom-bar icons, drawn here rather than pulled in.
 *
 * `androidx.compose.material:material-icons-*` is a large artifact — thousands
 * of vectors — and this app needs four. It is also not on the dependency list,
 * and adding a library to draw four shapes that already exist as six path
 * commands in the localhost app is the wrong trade.
 *
 * These ARE those shapes: the same paths the workbench's `<nav>` draws inline,
 * on the same 24-unit grid, so the two apps carry the same marks.
 */
private fun navIcon(name: String, build: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit) =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply(build).build()

private fun ImageVector.Builder.stroke(
    pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
) = path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 1.7f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = pathBuilder
)

/** A dial: the gallery of designs. */
val IconDesigns: ImageVector = navIcon("designs") {
    stroke {
        moveTo(12f, 4f); arcToRelative(8f, 8f, 0f, true, true, -0.01f, 0f); close()
    }
    stroke {
        moveTo(12f, 9f); arcToRelative(3f, 3f, 0f, true, true, -0.01f, 0f); close()
    }
}

/** Sliders: the studio. */
val IconStudio: ImageVector = navIcon("studio") {
    stroke { moveTo(4f, 7f); lineTo(20f, 7f) }
    stroke { moveTo(4f, 12f); lineTo(20f, 12f) }
    stroke { moveTo(4f, 17f); lineTo(20f, 17f) }
    stroke { moveTo(9f, 5.2f); arcToRelative(1.8f, 1.8f, 0f, true, true, -0.01f, 0f); close() }
    stroke { moveTo(15f, 10.2f); arcToRelative(1.8f, 1.8f, 0f, true, true, -0.01f, 0f); close() }
    stroke { moveTo(7f, 15.2f); arcToRelative(1.8f, 1.8f, 0f, true, true, -0.01f, 0f); close() }
}

/** A bookmark: the faces you kept. */
val IconMine: ImageVector = navIcon("mine") {
    stroke { moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(12f, 16f); lineTo(5f, 20f); close() }
}

/** An information mark. */
val IconAbout: ImageVector = navIcon("about") {
    stroke { moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, true, -0.01f, 0f); close() }
    stroke { moveTo(12f, 11f); lineTo(12f, 16f) }
    stroke { moveTo(12f, 7.6f); lineTo(12f, 8.2f) }
}
