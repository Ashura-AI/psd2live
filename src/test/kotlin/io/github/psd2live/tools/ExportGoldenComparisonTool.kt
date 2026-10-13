package io.github.psd2live.tools

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3GraphIndex
import org.umamo.interop.cmo3.Cmo3Import
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compare reports, resolving CAN3's references to the random part GUIDs of its companion CMO3 by part ID. */
class ExportGoldenComparisonTool {
    @Test fun compare() {
        requireTools()
        val root = output("export-golden")
        val before = setting("PSD2LIVE_GOLDEN_BEFORE", "baseline")
        val after = setting("PSD2LIVE_GOLDEN_AFTER", "optimized")
        fun report(label: String) = File(root, "$label.txt").readLines().associate { line ->
            val fields = line.split(' ', limit = 3)
            "${fields[0]} ${fields[1]}" to fields[2]
        }
        val original = report(before)
        val current = report(after)
        assertEquals(original.keys, current.keys)
        var references = 0
        for ((key, digest) in original) {
            val (variant, file) = key.split(' ', limit = 2)
            if (!file.endsWith(".can3")) assertEquals(digest, current[key], key)
            else {
                fun normalized(label: String): String {
                    val dir = File(root, "$label/$variant")
                    val source = Cmo3.read(File(dir, file.removeSuffix(".can3") + ".cmo3").readBytes()).root as CModelSource
                    val parts = Cmo3GraphIndex(source).partByIdStr
                    var xml = CaffCodec.read(File(dir, file).readBytes()).byPath("main.xml")!!.content.decodeToString()
                    for ((id, part) in parts) {
                        val guid = Cmo3Import.uuidOf(part.guid) ?: continue
                        xml = xml.replace("uuid=\"$guid\"", "uuid=\"part:$id\"")
                    }
                    return xml
                }
                assertEquals(normalized(before), normalized(after), key)
                references++
            }
        }
        File(root, "comparison.txt").writeText("$before vs $after: ${original.size - references} identical file/readback digests; " +
            "$references identical CAN3 XML payloads after resolving companion CMO3 part GUIDs by part ID.\n")
    }
}
