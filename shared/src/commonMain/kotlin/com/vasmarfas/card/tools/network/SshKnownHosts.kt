package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.tools.developer.Sha256
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

enum class SshHostStatus { NEW, TRUSTED, CHANGED }

@Serializable
data class SshHostEntry(val type: String, val fingerprint: String)

object SshKnownHosts {
    private const val KEY = "ssh.known_hosts"
    private val serializer = MapSerializer(String.serializer(), SshHostEntry.serializer())
    private val base64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT)

    fun fingerprint(keyBlob: ByteArray): String = "SHA256:" + base64.encode(Sha256.digest(keyBlob))

    fun id(host: String, port: Int): String = "${host.lowercase()}:$port"

    // one key per host and port, so a server that suddenly offers another key type counts as changed
    fun status(known: Map<String, SshHostEntry>, id: String, fingerprint: String): SshHostStatus {
        val entry = known[id] ?: return SshHostStatus.NEW
        return if (entry.fingerprint == fingerprint) SshHostStatus.TRUSTED else SshHostStatus.CHANGED
    }

    fun load(): Map<String, SshHostEntry> =
        runCatching { Prefs.store.get(KEY)?.let { Net.json.decodeFromString(serializer, it) } }.getOrNull() ?: emptyMap()

    fun save(known: Map<String, SshHostEntry>) = Prefs.store.put(KEY, Net.json.encodeToString(serializer, known))
}
