package com.geoffrey.cancitroen.usb

/** Frame CAN décodée depuis le flux ASCII slcan. */
data class CanFrame(
    val id: Int,             // 11-bit (ou 29-bit si extended)
    val data: ByteArray,
    val extended: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val hexId: String
        get() = if (extended) "%08X".format(id) else "%03X".format(id)
    val hexData: String
        get() = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanFrame) return false
        return id == other.id && extended == other.extended && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var h = id; h = 31 * h + (if (extended) 1 else 0); h = 31 * h + data.contentHashCode(); return h
    }
}
