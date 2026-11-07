package com.paniuthche.app

import org.json.JSONObject
import java.util.UUID

const val MAX_HOPS = 5

data class SosMessage(
    val id: String = UUID.randomUUID().toString(),
    val people: Int,
    val elderly: Boolean,
    val children: Boolean,
    val pregnant: Boolean,
    val note: String,
    val createdAt: Long = System.currentTimeMillis(),
    val hops: Int = 0,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("id", id).put("people", people).put("elderly", elderly).put("children", children)
        .put("pregnant", pregnant).put("note", note).put("createdAt", createdAt).put("hops", hops)
        .toString().toByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): SosMessage = JSONObject(String(bytes)).run {
            SosMessage(
                id = getString("id"), people = getInt("people"), elderly = getBoolean("elderly"),
                children = getBoolean("children"), pregnant = getBoolean("pregnant"),
                note = getString("note"), createdAt = getLong("createdAt"), hops = getInt("hops"),
            )
        }
    }
}

/** Returns the copy to store and forward, or null if already seen or out of hops. */
fun relayCopy(msg: SosMessage, seenIds: Set<String>): SosMessage? =
    if (msg.id in seenIds || msg.hops >= MAX_HOPS) null else msg.copy(hops = msg.hops + 1)
