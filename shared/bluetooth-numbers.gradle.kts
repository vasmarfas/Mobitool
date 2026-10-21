import java.net.URI
import java.time.LocalDate

// files/bluetooth-companies.tsv and files/bluetooth-uuids.tsv from the Bluetooth SIG assigned numbers: the
// company in manufacturer data and the owner of a 16-bit service UUID. Run by hand before a release:
// ./gradlew :shared:updateBluetoothNumbers

tasks.register("updateBluetoothNumbers") {
    group = "resources"
    description = "Rebuilds the Bluetooth company and 16-bit UUID tables from the Bluetooth SIG assigned numbers"
    val files = layout.projectDirectory.dir("src/commonMain/composeResources/files").asFile
    doLast {
        val base = "https://bitbucket.org/bluetooth-SIG/public/raw/main/assigned_numbers/"
        val entry = Regex("""-\s*(?:value|uuid):\s*0x([0-9A-Fa-f]+)\s*\n\s*name:\s*(.+)""")

        fun entries(path: String): List<Pair<String, String>> {
            val text = URI(base + path).toURL().readText()
            return entry.findAll(text).map { match ->
                val raw = match.groupValues[2].trim()
                val name = when {
                    raw.startsWith("'") -> raw.removeSurrounding("'").replace("''", "'")
                    raw.startsWith("\"") -> raw.removeSurrounding("\"").replace("\\\"", "\"")
                    else -> raw
                }
                match.groupValues[1].uppercase().padStart(4, '0') to name.replace('\t', ' ')
            }.toList()
        }

        fun write(name: String, rows: List<Pair<String, String>>) {
            val text = "#${LocalDate.now()}\n" + rows.distinctBy { it.first }.sortedBy { it.first }.joinToString("\n") { "${it.first}\t${it.second}" } + "\n"
            files.resolve(name).writeText(text)
            println("$name: ${rows.size} entries")
        }

        write("bluetooth-companies.tsv", entries("company_identifiers/company_identifiers.yaml"))
        write("bluetooth-uuids.tsv", entries("uuids/service_uuids.yaml") + entries("uuids/member_uuids.yaml"))
    }
}
