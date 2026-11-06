package com.projectx.synth

import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================================
//  Presets (v0.10): opslaan, laden, hernoemen en verwijderen van klanken.
//  Een preset bevat alle klankinstellingen van Osc A en B en de Mix; het keyboard niet.
//  Init = de beginwaarden van de app; Init staat niet in het bestand en kan dus niet
//  overschreven of verwijderd worden.
//  Opslag: één bestand presets.json in de eigen opslag van de app (blijft bewaard bij een
//  nieuwe versie, verdwijnt bij het verwijderen van de app).
// ============================================================================

/** Instellingen van één oscillator, zoals ze in een preset staan. */
data class OscData(
    val on: Boolean,
    val table: Int,
    val position: Float,
    val knob: Float,
    val octave: Int,
    val cent: Float,
    val pan: Float,
)

/** Alle klankinstellingen samen. */
data class PresetData(val a: OscData, val b: OscData, val mix: Float) {
    companion object {
        private val OSC_INIT = OscData(false, 0, 0f, SynthState.KNOB_DEFAULT, 0, 0f, 0f)
        val INIT = PresetData(OSC_INIT, OSC_INIT, SynthState.MIX_DEFAULT)
    }
}

class Preset(val name: String, val data: PresetData)

object Presets {
    const val INIT_NAME = "Init"
    const val MAX_NAME = 24

    /** Opgeslagen presets, alfabetisch (Init staat er niet in). */
    val list = mutableStateListOf<Preset>()

    /** Naam van de geladen preset (Init als er niets geladen is). */
    var currentName by mutableStateOf(INIT_NAME)
        private set
    /** De instellingen zoals ze geladen of opgeslagen werden: verschil = sterretje. */
    private var reference by mutableStateOf(PresetData.INIT)

    /** Verandert bij elke wijziging van de lijst (zodat het scherm opnieuw tekent). */
    var version by mutableIntStateOf(0)
        private set

    private var file: AtomicFile? = null

    val isInit get() = currentName == INIT_NAME

    /** Is er iets gewijzigd sinds het laden of opslaan? (leest alle knoppen: scherm tekent mee) */
    fun dirty(): Boolean = SynthState.snapshot() != reference

    fun displayName(): String = if (dirty()) "$currentName *" else currentName

    // --- bestand -----------------------------------------------------------------------
    fun init(dir: File) {
        if (file != null) return
        val f = AtomicFile(File(dir, "presets.json"))
        file = f
        list.clear()
        try {
            if (f.baseFile.exists()) {
                val root = JSONObject(String(f.readFully(), Charsets.UTF_8))
                val arr = root.optJSONArray("presets") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("name").trim()
                    if (name.isEmpty() || isReserved(name) || find(name) != null) continue
                    list.add(Preset(name, PresetData(oscFromJson(o.getJSONObject("A")),
                        oscFromJson(o.getJSONObject("B")), o.getDouble("mix").toFloat())))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ProjectX", "presets.json kon niet gelezen worden", e)
        }
        sort()
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("mix", p.data.mix.toDouble())
                put("A", oscToJson(p.data.a))
                put("B", oscToJson(p.data.b))
            })
        }
        val bytes = JSONObject().put("version", 1).put("presets", arr).toString(2).toByteArray(Charsets.UTF_8)
        val out = f.startWrite()
        try {
            out.write(bytes)
            f.finishWrite(out)          // pas nu vervangt het nieuwe bestand het oude
        } catch (e: Exception) {
            f.failWrite(out)
            android.util.Log.e("ProjectX", "presets.json kon niet bewaard worden", e)
        }
    }

    private fun oscToJson(d: OscData) = JSONObject().apply {
        put("on", d.on)
        put("table", d.table)
        put("tableName", Wavetables.name(d.table))
        put("position", d.position.toDouble())
        put("amp", d.knob.toDouble())
        put("octave", d.octave)
        put("cent", d.cent.toDouble())
        put("pan", d.pan.toDouble())
    }

    private fun oscFromJson(o: JSONObject) = OscData(
        on = o.optBoolean("on", false),
        table = o.optInt("table", 0),
        position = o.optDouble("position", 0.0).toFloat().coerceIn(0f, 1f),
        knob = o.optDouble("amp", SynthState.KNOB_DEFAULT.toDouble()).toFloat().coerceIn(0f, 1f),
        octave = o.optInt("octave", 0).coerceIn(-2, 1),
        cent = o.optDouble("cent", 0.0).toFloat().coerceIn(-100f, 100f),
        pan = o.optDouble("pan", 0.0).toFloat().coerceIn(-100f, 100f),
    )

    private fun sort() {
        list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        version++
    }

    // --- namen ---------------------------------------------------------------------------
    fun isReserved(name: String) = name.trim().equals(INIT_NAME, ignoreCase = true)
    fun find(name: String): Preset? = list.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Waarom deze naam niet kan (of null als hij goed is). [except] = de naam die hernoemd wordt. */
    fun nameProblem(name: String, except: String? = null): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Enter a name"
            isReserved(n) -> "“Init” is reserved"
            find(n) != null && !n.equals(except, ignoreCase = true) -> "Name already exists"
            else -> null
        }
    }

    /** Voorstel bij "Save as new": de huidige naam met het eerste vrije volgnummer (2, 3, ...). */
    fun suggestName(): String {
        val base = currentName.replace(Regex("\\s+\\d+$"), "").ifBlank { "Preset" }.take(MAX_NAME - 3)
        var k = 2
        while (nameProblem("$base $k") != null) k++
        return "$base $k"
    }

    // --- handelingen ------------------------------------------------------------------------
    /** Preset (of Init bij null) laden: klank instellen, keyboard dicht, latch uit. */
    fun load(p: Preset?) {
        val d = p?.data ?: PresetData.INIT
        SynthState.apply(d)
        Keys.reset()
        clampScroll()
        SynthState.menuOpen = false
        currentName = p?.name ?: INIT_NAME
        reference = d
    }

    /** De geladen preset overschrijven met de huidige instellingen (niet bij Init). */
    fun overwrite() {
        if (isInit) return
        val i = list.indexOfFirst { it.name == currentName }
        val d = SynthState.snapshot()
        if (i >= 0) list[i] = Preset(currentName, d) else list.add(Preset(currentName, d))
        reference = d
        sort(); save()
    }

    /** De huidige instellingen bewaren als nieuwe preset [name] (wordt de geladen preset). */
    fun saveAsNew(name: String): Boolean {
        if (nameProblem(name) != null) return false
        val n = name.trim()
        val d = SynthState.snapshot()
        list.add(Preset(n, d))
        currentName = n
        reference = d
        sort(); save()
        return true
    }

    fun rename(old: String, name: String): Boolean {
        if (nameProblem(name, except = old) != null) return false
        val i = list.indexOfFirst { it.name == old }
        if (i < 0) return false
        val n = name.trim()
        list[i] = Preset(n, list[i].data)
        if (currentName == old) currentName = n
        sort(); save()
        return true
    }

    /** Verwijderen. Was het de geladen preset, dan blijft de klank staan, maar onder "Init" (met *). */
    fun delete(name: String) {
        list.removeAll { it.name == name }
        if (currentName == name) { currentName = INIT_NAME; reference = PresetData.INIT }
        sort(); save()
    }
}

/** Welk preset-venster open is (en voor welke preset of handeling). */
object PresetUi {
    enum class Mode { NONE, LIST, SAVE, NAME_NEW, NAME_RENAME, DISCARD, DELETE }

    var mode by mutableStateOf(Mode.NONE)
    val open get() = mode != Mode.NONE

    /** Preset waar het venster over gaat (hernoemen, verwijderen). */
    var target by mutableStateOf<String?>(null)
    /** Wat er moet gebeuren na "Discard": een preset laden of de app sluiten. */
    var pendingLoad: Preset? = null
    var pendingLoadInit = false
    var pendingExit = false
    /** Tekst in het naamveld. */
    var nameText by mutableStateOf("")
    /** Hoe ver de presetlijst gescrold is (ontwerpeenheden). */
    var listScroll by mutableStateOf(0f)

    /** Sluit de app (ingevuld door MainActivity). */
    var exitApp: (() -> Unit)? = null

    fun close() { mode = Mode.NONE; target = null; pendingLoad = null; pendingLoadInit = false; pendingExit = false }

    fun openList() {
        listScroll = 0f
        // de geladen preset in beeld brengen
        val i = Presets.list.indexOfFirst { it.name == Presets.currentName }
        if (i >= 0) listScroll = ((i + 1) * PRESET_ROW_STEP - 3 * PRESET_ROW_STEP).coerceAtLeast(0f)
        mode = Mode.LIST
    }

    /** Preset laden; bij niet-opgeslagen wijzigingen eerst "Discard changes?". */
    fun requestLoad(p: Preset?) {
        if (Presets.dirty()) {
            pendingLoad = p; pendingLoadInit = p == null; pendingExit = false
            mode = Mode.DISCARD
        } else {
            Presets.load(p); close()
        }
    }

    /** Exit-icoon: app sluiten; bij niet-opgeslagen wijzigingen eerst "Discard changes?". */
    fun requestExit() {
        if (Presets.dirty()) {
            pendingLoad = null; pendingLoadInit = false; pendingExit = true
            mode = Mode.DISCARD
        } else exitNow()
    }

    fun discardConfirmed() {
        when {
            pendingExit -> exitNow()
            else -> { Presets.load(pendingLoad); close() }
        }
    }

    private fun exitNow() {
        close()
        Presets.load(null)          // bij een volgende start begint alles weer bij Init
        exitApp?.invoke()
    }
}

/** Hoogte van een rij in de presetlijst + tussenruimte (ontwerpeenheden). */
internal const val PRESET_ROW_H = 16.5f
internal const val PRESET_ROW_STEP = 18f
