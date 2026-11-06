package com.projectx.synth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================================
//  Presetbalk, presetlijst en de kleine vensters (v0.10), nagetekend naar
//  design/GUI onderdelen Claude/preset-lijst-voorbeeld.svg en preset-vensters-voorbeeld.svg,
//  en het exit-icoon uit Synth1OscA-v6.svg. Maten in ontwerpeenheden (508 x 317,5).
// ============================================================================

private val ColRed = Color(0xFFC24040)          // knop waarbij iets verloren gaat
private val ColDisabled = Color(0xFF2B2B2B)
private val ColDisabledText = Color(0xFF666666)

// --- Presetbalk in de bovenbalk (vak "Preset systeem") en exit-icoon ----------------
internal val rPresetName = Area(202.5f, 6.2f, 85f, 24.6f)
internal val rPresetSave = Area(291.2f, 6.2f, 22.6f, 24.6f)
private const val EXIT_SCALE = 0.63139f
private val iconExit = Offset(2.474f, 3.930f)
internal val rExit = Area(0f, 0f, MENU_EDGE, MENU_EDGE)

// --- Kleine vensters ------------------------------------------------------------------
private const val DLG_W = 190f
private const val DLG_BTN_W = 80f
private const val DLG_BTN_H = 18f

private fun dialogHeight(m: PresetUi.Mode) = when (m) {
    PresetUi.Mode.NAME_NEW, PresetUi.Mode.NAME_RENAME -> 86f
    else -> 66f
}

/** Plaats van het kleine venster: midden boven, zodat het Android-toetsenbord eronder past. */
private fun dialogArea(): Area {
    val cx = (MENU_EDGE + 481.542f) / 2
    return Area(cx - DLG_W / 2, MENU_EDGE + 10f, DLG_W, dialogHeight(PresetUi.mode))
}

/** Knoppen onderaan rechts: [0] = links (annuleren/tweede keuze), [1] = rechts (hoofdkeuze). */
private fun dialogButton(i: Int): Area {
    val d = dialogArea()
    val x = d.x + d.w - 8f - DLG_BTN_W * (2 - i) - 6f * (1 - i)
    return Area(x, d.bottom - 26f, DLG_BTN_W, DLG_BTN_H)
}

private fun dialogClose(): Area { val d = dialogArea(); return Area(d.x + d.w - 26f, d.y, 26f, 24f) }
private fun nameField(): Area { val d = dialogArea(); return Area(d.x + 8f, d.y + 24f, d.w - 16f, 20f) }

// --- Presetlijst (zelfde plaats als het wavetable-venster) ---------------------------
private fun listArea(): Area {
    val w = menuWindow()
    return Area(w.x + 8f, w.y + 24f, w.w - 24f, w.h - 32f)
}
private fun listRows() = Presets.list.size + 1          // + Init
private fun listMaxScroll(): Float = max(0f, listRows() * PRESET_ROW_STEP - (PRESET_ROW_STEP - PRESET_ROW_H) - listArea().h)
private fun listClose(): Area { val w = menuWindow(); return Area(w.x + w.w - 30f, w.y, 30f, 24f) }

// ----------------------------------------------------------------------------
//  Laag met de vensters. Alleen als er een venster open is, vangt deze laag de aanrakingen
//  op (ook die op het keyboard); anders merkt hij niets.
// ----------------------------------------------------------------------------
@Composable
internal fun PresetLayer(measurer: TextMeasurer) {
    val open = PresetUi.open
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (open) Modifier.pointerInput(Unit) { handlePresetTouches() } else Modifier
            ),
        ) {
            if (PresetUi.open) drawPresetUi(Fit(IntSize(size.width.toInt(), size.height.toInt())), measurer)
        }
        val mode = PresetUi.mode
        if (mode == PresetUi.Mode.NAME_NEW || mode == PresetUi.Mode.NAME_RENAME) {
            NameField(Fit(IntSize(constraints.maxWidth, constraints.maxHeight)), mode)
        }
    }
}

/** Tekstveld voor de naam (Android-toetsenbord), precies over het getekende veld. */
@Composable
private fun NameField(fit: Fit, mode: PresetUi.Mode) {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var value by remember(mode, PresetUi.target) {
        mutableStateOf(TextFieldValue(PresetUi.nameText, TextRange(PresetUi.nameText.length)))
    }
    val r = nameField()
    LaunchedEffect(mode, PresetUi.target) {
        focus.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    BasicTextField(
        value = value,
        onValueChange = {
            val t = it.text.replace('\n', ' ').take(Presets.MAX_NAME)
            value = if (t == it.text) it else it.copy(text = t, selection = TextRange(t.length))
            PresetUi.nameText = t
        },
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = Lato, fontWeight = FontWeight.Normal, color = ColText,
            fontSize = with(density) { (10f * fit.s).toSp() },
        ),
        cursorBrush = SolidColor(ColText),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { confirmName() }),
        modifier = Modifier
            .offset { IntOffset(fit.x(r.x + 4f).roundToInt(), fit.y(r.y).roundToInt()) }
            .size(with(density) { ((r.w - 8f) * fit.s).toDp() }, with(density) { (r.h * fit.s).toDp() })
            .focusRequester(focus),
        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
    )
}

// ----------------------------------------------------------------------------
//  Bediening
// ----------------------------------------------------------------------------

/** Tik op de presetbalk of het exit-icoon (vanuit de gewone bediening). */
internal fun presetBarTap(target: String) {
    SynthState.menuOpen = false
    when (target) {
        "name" -> PresetUi.openList()
        "save" -> PresetUi.mode = PresetUi.Mode.SAVE
        "exit" -> PresetUi.requestExit()
    }
}

private suspend fun PointerInputScope.handlePresetTouches() {
    awaitEachGesture {
        val fit = Fit(size)
        val down = awaitFirstDown()
        down.consume()
        val ux = fit.toUnitX(down.position.x)
        val uy = fit.toUnitY(down.position.y)
        val inList = PresetUi.mode == PresetUi.Mode.LIST && listArea().contains(ux, uy)
        var moved = 0f
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val d = change.positionChange()
            moved += abs(d.x) + abs(d.y)
            if (inList) PresetUi.listScroll = (PresetUi.listScroll - d.y / fit.s).coerceIn(0f, listMaxScroll())
            change.consume()
        }
        if (moved < 3f * fit.s) presetTap(ux, uy)
    }
}

private fun presetTap(ux: Float, uy: Float) {
    when (PresetUi.mode) {
        PresetUi.Mode.NONE -> {}
        PresetUi.Mode.LIST -> listTap(ux, uy)
        PresetUi.Mode.SAVE -> when {
            !dialogArea().contains(ux, uy) || dialogClose().contains(ux, uy) -> PresetUi.close()
            dialogButton(0).contains(ux, uy) -> if (!Presets.isInit) { Presets.overwrite(); PresetUi.close() }
            dialogButton(1).contains(ux, uy) -> {
                PresetUi.nameText = Presets.suggestName()
                PresetUi.target = null
                PresetUi.mode = PresetUi.Mode.NAME_NEW
            }
        }
        PresetUi.Mode.NAME_NEW, PresetUi.Mode.NAME_RENAME -> when {
            !dialogArea().contains(ux, uy) || dialogClose().contains(ux, uy) || dialogButton(0).contains(ux, uy) ->
                cancelName()
            dialogButton(1).contains(ux, uy) -> confirmName()
        }
        PresetUi.Mode.DISCARD -> when {
            !dialogArea().contains(ux, uy) || dialogClose().contains(ux, uy) || dialogButton(0).contains(ux, uy) ->
                PresetUi.close()
            dialogButton(1).contains(ux, uy) -> PresetUi.discardConfirmed()
        }
        PresetUi.Mode.DELETE -> when {
            !dialogArea().contains(ux, uy) || dialogClose().contains(ux, uy) || dialogButton(0).contains(ux, uy) ->
                backToList()
            dialogButton(1).contains(ux, uy) -> {
                PresetUi.target?.let { Presets.delete(it) }
                backToList()
            }
        }
    }
}

private fun backToList() {
    PresetUi.target = null
    PresetUi.listScroll = PresetUi.listScroll.coerceIn(0f, listMaxScroll())
    PresetUi.mode = PresetUi.Mode.LIST
}

private fun cancelName() {
    if (PresetUi.mode == PresetUi.Mode.NAME_RENAME) backToList() else PresetUi.close()
}

/** "Save" in het naamvenster (of Klaar op het Android-toetsenbord). */
private fun confirmName() {
    val name = PresetUi.nameText
    when (PresetUi.mode) {
        PresetUi.Mode.NAME_NEW -> if (Presets.saveAsNew(name)) PresetUi.close()
        PresetUi.Mode.NAME_RENAME -> {
            val old = PresetUi.target ?: return
            if (Presets.rename(old, name)) backToList()
        }
        else -> {}
    }
}

private fun nameProblem(): String? = when (PresetUi.mode) {
    PresetUi.Mode.NAME_NEW -> Presets.nameProblem(PresetUi.nameText)
    PresetUi.Mode.NAME_RENAME -> Presets.nameProblem(PresetUi.nameText, except = PresetUi.target)
    else -> null
}

private fun listTap(ux: Float, uy: Float) {
    val win = menuWindow()
    if (!win.contains(ux, uy) || listClose().contains(ux, uy)) { PresetUi.close(); return }
    val area = listArea()
    if (!area.contains(ux, uy)) return
    val yIn = uy - area.y + PresetUi.listScroll
    val row = floor(yIn / PRESET_ROW_STEP).toInt()
    if (row < 0 || row >= listRows() || yIn - row * PRESET_ROW_STEP > PRESET_ROW_H) return
    if (row == 0) { PresetUi.requestLoad(null); return }
    val p = Presets.list[row - 1]
    val right = area.x + area.w
    when {
        ux >= right - 20f -> { PresetUi.target = p.name; PresetUi.mode = PresetUi.Mode.DELETE }
        ux >= right - 40f -> {
            PresetUi.target = p.name
            PresetUi.nameText = p.name
            PresetUi.mode = PresetUi.Mode.NAME_RENAME
        }
        else -> PresetUi.requestLoad(p)
    }
}

// ----------------------------------------------------------------------------
//  Tekenen
// ----------------------------------------------------------------------------

/** Tekst links uitgelijnd op ([x], basislijn [y]); te breed = kleiner tot het past. */
private fun DrawScope.textLeft(
    fit: Fit, measurer: TextMeasurer, s: String, x: Float, y: Float, size: Float, bold: Boolean, color: Color,
    maxW: Float = Float.MAX_VALUE,
) {
    var style = TextStyle(fontFamily = Lato, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = (size * fit.s).toSp())
    var layout = measurer.measure(s, style)
    val maxPx = maxW * fit.s
    if (layout.size.width > maxPx) {
        style = style.copy(fontSize = (size * fit.s * maxPx / layout.size.width).toSp())
        layout = measurer.measure(s, style)
    }
    drawText(layout, color, Offset(fit.x(x), fit.y(y) - layout.firstBaseline))
}

private fun DrawScope.roundRect(fit: Fit, r: Area, color: Color, radius: Float, stroke: Float = 0f) {
    val tl = Offset(fit.x(r.x), fit.y(r.y))
    val sz = Size(r.w * fit.s, r.h * fit.s)
    if (stroke > 0f) drawRoundRect(color, tl, sz, CornerRadius(radius * fit.s), style = Stroke(stroke * fit.s))
    else drawRoundRect(color, tl, sz, CornerRadius(radius * fit.s))
}

private fun DrawScope.closeCross(fit: Fit, x: Float, y: Float) {
    val st = 1.6f * fit.s
    drawLine(ColIcon, Offset(fit.x(x), fit.y(y)), Offset(fit.x(x + 8f), fit.y(y + 8f)), st, StrokeCap.Round)
    drawLine(ColIcon, Offset(fit.x(x + 8f), fit.y(y)), Offset(fit.x(x), fit.y(y + 8f)), st, StrokeCap.Round)
}

/** Venster met lichtgrijs streepje bovenaan (zelfde stijl als het wavetable-venster). */
private fun DrawScope.windowFrame(fit: Fit, r: Area) {
    roundRect(fit, r, ColDark, 2f)
    roundRect(fit, r, ColTrack, 2f, stroke = 0.4f)
    drawRoundRect(ColIcon, Offset(fit.x(r.x), fit.y(r.y)), Size(r.w * fit.s, 2.6f * fit.s), CornerRadius(1.3f * fit.s))
}

private enum class BtnKind { NORMAL, PRIMARY, DANGER, DISABLED }

private fun DrawScope.dialogBtn(fit: Fit, measurer: TextMeasurer, r: Area, label: String, kind: BtnKind) {
    val (fill, tc) = when (kind) {
        BtnKind.PRIMARY -> ColKnob to ColDark
        BtnKind.DANGER -> ColRed to ColText
        BtnKind.DISABLED -> ColDisabled to ColDisabledText
        BtnKind.NORMAL -> ColButton to ColText
    }
    roundRect(fit, r, fill, 1.6f)
    text(fit, measurer, label, Offset(r.cx, r.y + 12.2f), 9f, true, tc, maxWidthUnits = r.w - 4f)
}

/** Presetbalk: naam (met * bij wijzigingen) en het opslaan-icoon. Getekend in de bovenbalk. */
internal fun DrawScope.drawPresetBar(fit: Fit, measurer: TextMeasurer) {
    roundRect(fit, rPresetName, ColPanel, 1.6f)
    textLeft(fit, measurer, Presets.displayName(), rPresetName.x + 5f, rPresetName.y + 16.3f, 11.29f, false, ColText,
        maxW = rPresetName.w - 18f)
    val cx = rPresetName.x + rPresetName.w - 7.5f
    val cy = rPresetName.y + 11f
    val chevron = Path().apply {
        moveTo(fit.x(cx - 3f), fit.y(cy)); lineTo(fit.x(cx), fit.y(cy + 3f)); lineTo(fit.x(cx + 3f), fit.y(cy))
    }
    drawPath(chevron, ColIcon, style = Stroke(1.2f * fit.s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    roundRect(fit, rPresetSave, ColPanel, 1.6f)
    drawSaveIcon(fit, Offset(rPresetSave.cx, rPresetSave.y + rPresetSave.h / 2), 0.36f, ColIcon)
}

/** Exit-icoon linksboven (design: groep "icoon-sluiten" in Synth1OscA-v6.svg). */
internal fun DrawScope.drawExitIcon(fit: Fit) = icon(fit, iconExit, EXIT_SCALE) {
    val st = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val frame = Path().apply {
        moveTo(23f, 9f); lineTo(12f, 9f)
        quadraticTo(9f, 9f, 9f, 12f); lineTo(9f, 36f)
        quadraticTo(9f, 39f, 12f, 39f); lineTo(23f, 39f)
    }
    drawPath(frame, ColIcon, style = st)
    drawLine(ColIcon, Offset(19f, 24f), Offset(39f, 24f), 3f, StrokeCap.Round)
    val head = Path().apply { moveTo(32f, 17f); lineTo(39f, 24f); lineTo(32f, 31f) }
    drawPath(head, ColIcon, style = st)
}

/** Icoon op een raster van 48 x 48 met middelpunt [c] en schaal [k] (ontwerpeenheden per rastereenheid). */
private inline fun DrawScope.icon48(fit: Fit, c: Offset, k: Float, block: DrawScope.() -> Unit) =
    icon(fit, Offset(c.x - 24f * k, c.y - 24f * k), k, block)

private fun DrawScope.drawSaveIcon(fit: Fit, c: Offset, k: Float, col: Color) = icon48(fit, c, k) {
    val st = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val body = Path().apply {
        moveTo(11f, 8f); lineTo(31f, 8f); lineTo(40f, 17f); lineTo(40f, 37f)
        quadraticTo(40f, 40f, 37f, 40f); lineTo(11f, 40f)
        quadraticTo(8f, 40f, 8f, 37f); lineTo(8f, 11f)
        quadraticTo(8f, 8f, 11f, 8f); close()
    }
    drawPath(body, col, style = st)
    drawPath(Path().apply { moveTo(15f, 8f); lineTo(15f, 16f); lineTo(29f, 16f); lineTo(29f, 8f) }, col, style = st)
    drawPath(Path().apply { moveTo(14f, 40f); lineTo(14f, 27f); lineTo(34f, 27f); lineTo(34f, 40f) }, col, style = st)
}

private fun DrawScope.drawRenameIcon(fit: Fit, c: Offset, k: Float, col: Color) = icon48(fit, c, k) {
    val st = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(31f, 9f); lineTo(39f, 17f); lineTo(18f, 38f); lineTo(10f, 38f); lineTo(10f, 30f); close() },
        col, style = st)
    drawLine(col, Offset(26f, 14f), Offset(34f, 22f), 3.5f, StrokeCap.Round)
}

private fun DrawScope.drawDeleteIcon(fit: Fit, c: Offset, k: Float, col: Color) = icon48(fit, c, k) {
    val st = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawLine(col, Offset(8f, 13f), Offset(40f, 13f), 3.5f, StrokeCap.Round)
    drawPath(Path().apply { moveTo(19f, 13f); lineTo(19f, 9f); lineTo(29f, 9f); lineTo(29f, 13f) }, col, style = st)
    drawPath(Path().apply { moveTo(12f, 13f); lineTo(14.5f, 40f); lineTo(33.5f, 40f); lineTo(36f, 13f) }, col, style = st)
    drawLine(col, Offset(21f, 19f), Offset(21f, 34f), 3.5f, StrokeCap.Round)
    drawLine(col, Offset(27f, 19f), Offset(27f, 34f), 3.5f, StrokeCap.Round)
}

private fun DrawScope.drawPresetUi(fit: Fit, measurer: TextMeasurer) {
    // alles onder de bovenbalk en rechts van de linkerbalk dimmen (ook het keyboard)
    drawRect(Color.Black.copy(alpha = 0.45f), Offset(fit.x(MENU_EDGE), fit.y(MENU_EDGE)),
        Size((DESIGN_W - MENU_EDGE) * fit.s, (DESIGN_H - MENU_EDGE) * fit.s))
    when (PresetUi.mode) {
        PresetUi.Mode.NONE -> {}
        PresetUi.Mode.LIST -> drawList(fit, measurer)
        else -> drawDialog(fit, measurer)
    }
}

private fun DrawScope.drawList(fit: Fit, measurer: TextMeasurer) {
    val win = menuWindow()
    windowFrame(fit, win)
    textLeft(fit, measurer, "Presets", win.x + 8f, win.y + 16.5f, 11.29f, true, ColText)
    closeCross(fit, win.x + win.w - 16f, win.y + 8f)
    val area = listArea()
    val sc = PresetUi.listScroll.coerceIn(0f, listMaxScroll())
    if (Presets.version < 0) return   // lezen = opnieuw tekenen als de lijst verandert
    clipRect(fit.x(area.x), fit.y(area.y), fit.x(area.x + area.w), fit.y(area.bottom)) {
        val first = max(0, floor(sc / PRESET_ROW_STEP).toInt())
        val last = minOf(listRows() - 1, ((sc + area.h) / PRESET_ROW_STEP).toInt())
        for (i in first..last) {
            val y = area.y + i * PRESET_ROW_STEP - sc
            val name = if (i == 0) Presets.INIT_NAME else Presets.list[i - 1].name
            val loaded = name == Presets.currentName
            val row = Area(area.x, y, area.w, PRESET_ROW_H)
            roundRect(fit, row, ColTile, 1.2f)
            if (loaded) rect(fit, Area(area.x, y, 1.6f, PRESET_ROW_H), ColIcon)
            textLeft(fit, measurer, name, area.x + 6.9f, y + 11.6f, 9f, loaded, ColText, maxW = area.w - 50f)
            if (i > 0) {
                val cy = y + PRESET_ROW_H / 2
                drawRenameIcon(fit, Offset(area.x + area.w - 30f, cy), 0.27f, ColIcon)
                drawDeleteIcon(fit, Offset(area.x + area.w - 10f, cy), 0.27f, ColIcon)
            }
        }
    }
    // scrollbalk (alleen als niet alles past)
    val maxS = listMaxScroll()
    if (maxS > 0f) {
        val tx = win.x + win.w - 11.66f
        val total = maxS + area.h
        val len = max(12f, area.h * area.h / total)
        val top = area.y + (area.h - len) * sc / maxS
        roundRect(fit, Area(tx, area.y, 1.6f, area.h), ColPanel, 0.8f)
        roundRect(fit, Area(tx, top, 1.6f, len), ColIcon, 0.8f)
    }
}

private fun DrawScope.drawDialog(fit: Fit, measurer: TextMeasurer) {
    val d = dialogArea()
    windowFrame(fit, d)
    closeCross(fit, d.x + d.w - 16f, d.y + 8f)
    val mode = PresetUi.mode
    val title = when (mode) {
        PresetUi.Mode.SAVE -> "Save preset"
        PresetUi.Mode.NAME_NEW -> "Save as new"
        PresetUi.Mode.NAME_RENAME -> "Rename"
        PresetUi.Mode.DISCARD -> "Discard changes?"
        PresetUi.Mode.DELETE -> "Delete preset?"
        else -> ""
    }
    textLeft(fit, measurer, title, d.x + 8f, d.y + 16.5f, 11.29f, true, ColText, maxW = d.w - 40f)
    val body = when (mode) {
        PresetUi.Mode.SAVE -> Presets.currentName
        PresetUi.Mode.DISCARD -> "${Presets.currentName} has unsaved changes."
        PresetUi.Mode.DELETE -> "“${PresetUi.target ?: ""}” will be deleted."
        else -> null
    }
    if (body != null) textLeft(fit, measurer, body, d.x + 8f, d.y + 33f, 9f, false, ColIcon, maxW = d.w - 16f)
    when (mode) {
        PresetUi.Mode.SAVE -> {
            dialogBtn(fit, measurer, dialogButton(0), "Overwrite", if (Presets.isInit) BtnKind.DISABLED else BtnKind.NORMAL)
            dialogBtn(fit, measurer, dialogButton(1), "Save as new", BtnKind.PRIMARY)
        }
        PresetUi.Mode.NAME_NEW, PresetUi.Mode.NAME_RENAME -> {
            val f = nameField()
            roundRect(fit, f, ColTile, 1.2f)
            roundRect(fit, f, ColIcon, 1.2f, stroke = 0.4f)
            val problem = nameProblem()
            if (problem != null && PresetUi.nameText.isNotBlank()) {
                textLeft(fit, measurer, problem, d.x + 8f, f.bottom + 9.5f, 8f, false, ColRed)
            }
            dialogBtn(fit, measurer, dialogButton(0), "Cancel", BtnKind.NORMAL)
            dialogBtn(fit, measurer, dialogButton(1), "Save", if (problem == null) BtnKind.PRIMARY else BtnKind.DISABLED)
        }
        PresetUi.Mode.DISCARD -> {
            dialogBtn(fit, measurer, dialogButton(0), "Cancel", BtnKind.NORMAL)
            dialogBtn(fit, measurer, dialogButton(1), "Discard", BtnKind.DANGER)
        }
        PresetUi.Mode.DELETE -> {
            dialogBtn(fit, measurer, dialogButton(0), "Cancel", BtnKind.NORMAL)
            dialogBtn(fit, measurer, dialogButton(1), "Delete", BtnKind.DANGER)
        }
        else -> {}
    }
}
