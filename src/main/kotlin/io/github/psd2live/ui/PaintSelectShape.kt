package io.github.psd2live.ui

import io.github.psd2live.core.PaintSelectionOp
import io.github.psd2live.core.PaintSymmetry

/**
 * The faces of the paint selection tool, one tool the way the shape tool is: Photoshop's marquee, lasso and magic
 * wand. The number keys pick them while the tool is in hand.
 */
enum class PaintSelectShape(val labelKey: String) {
    RECTANGLE("editor.paintSelect.rectangle"),
    ELLIPSE("editor.paintSelect.ellipse"),
    /** Freehand: the outline follows the drag. */
    LASSO("editor.paintSelect.lasso"),
    /** Click by click; the first point, a double click or Enter closes it. */
    POLYGON("editor.paintSelect.polygon"),
    /** The pixels of the colour clicked, within the tolerance. */
    WAND("editor.paintSelect.wand"),
}

/** How a selection gesture meets the selection already there; Shift adds, Alt subtracts, both intersect. */
internal val PAINT_SELECT_OP_LABELS = listOf(
    PaintSelectionOp.REPLACE to "editor.paintSelect.replace",
    PaintSelectionOp.ADD to "editor.paintSelect.add",
    PaintSelectionOp.SUBTRACT to "editor.paintSelect.subtract",
    PaintSelectionOp.INTERSECT to "editor.paintSelect.intersect",
)

/** The mirror axes a stroke can be painted across, with their option labels. */
internal val PAINT_SYMMETRY_LABELS = listOf(
    PaintSymmetry.OFF to "editor.paint.symmetryOff",
    PaintSymmetry.VERTICAL to "editor.paint.symmetryVertical",
    PaintSymmetry.HORIZONTAL to "editor.paint.symmetryHorizontal",
    PaintSymmetry.BOTH to "editor.paint.symmetryBoth",
)

/** What the gradient runs between: the foreground to the background colour, or to transparent. */
enum class PaintGradientEnd(val labelKey: String) {
    BACKGROUND("editor.paintGradient.toBackground"),
    TRANSPARENT("editor.paintGradient.toTransparent"),
}
