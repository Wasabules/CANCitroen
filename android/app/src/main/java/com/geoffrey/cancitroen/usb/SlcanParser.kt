package com.geoffrey.cancitroen.usb

/**
 * Parser slcan ASCII (firmware CANable).
 *
 * Format des messages reçus :
 *   t<III><L><DD..>\r       — frame standard 11-bit, ex: "t1A03010203\r"
 *   T<IIIIIIII><L><DD..>\r  — frame extended 29-bit
 *   r<III><L>\r             — remote frame standard (ignoré)
 *   R<IIIIIIII><L>\r        — remote frame extended (ignoré)
 *
 * Format à émettre :
 *   t<III><L><DD..>\r        ex: "t1A02AABB\r" pour ID 0x1A0, DLC 2, data AA BB
 *
 * Commandes :
 *   S0..S8\r   — bitrate (S4=125k)
 *   O\r        — open channel
 *   C\r        — close channel
 */
class SlcanParser {

    /**
     * Ligne en cours, en octets ASCII bruts : décodée directement (nibbles hex)
     * sans passer par String/substring. ~185 trames/s en continu → le parseur
     * ne doit allouer que la [CanFrame] et son payload.
     */
    private val line = ByteArray(MAX_LINE_LEN)
    private var lineLen = 0

    /**
     * Flag de corruption : à true quand la ligne a dépassé [MAX_LINE_LEN]
     * (USB glitch / bytes perdus). On ignore alors tout jusqu'au prochain `\r`
     * pour ne pas commencer à parser une trame au milieu — sans ça, la trame
     * suivante propre était silencieusement tronquée.
     */
    private var corruptUntilNextCr = false

    /**
     * Reset complet du parser. À appeler au close → reopen du CANable : sinon
     * un `corruptUntilNextCr` ou une ligne non vide survivait au reconnect
     * et faisait ignorer la première frame propre.
     */
    fun reset() {
        lineLen = 0
        corruptUntilNextCr = false
    }

    /** Push des bytes lus ; [onFrame] est appelé pour chaque frame complète. */
    inline fun feed(chunk: ByteArray, len: Int = chunk.size, onFrame: (CanFrame) -> Unit) {
        for (i in 0 until len) {
            val frame = pushByte(chunk[i].toInt() and 0xFF)
            if (frame != null) onFrame(frame)
        }
    }

    /** Variante liste (tests, usages hors chemin chaud). */
    fun feed(chunk: ByteArray, len: Int = chunk.size): List<CanFrame> {
        val out = mutableListOf<CanFrame>()
        feed(chunk, len) { out += it }
        return out
    }

    /** Consomme un octet ; retourne la frame si c'était le `\r` d'une ligne valide. */
    @PublishedApi
    internal fun pushByte(c: Int): CanFrame? {
        if (c == 0x0D /* \r */) {
            val n = lineLen
            lineLen = 0
            if (corruptUntilNextCr) {
                android.util.Log.w(TAG, "Frame corrompue ignorée (overflow)")
                corruptUntilNextCr = false
                return null
            }
            return parseLine(n)
        }
        if (c in 0x20..0x7E /* printable */ && !corruptUntilNextCr) {
            if (lineLen == MAX_LINE_LEN) {
                // Overflow : on flag tout le reste jusqu'au prochain CR comme
                // corrompu (au lieu de juste vider, qui faisait perdre aussi le
                // début de la trame suivante).
                corruptUntilNextCr = true
                lineLen = 0
            } else {
                line[lineLen++] = c.toByte()
            }
        }
        return null
    }

    private fun parseLine(n: Int): CanFrame? {
        if (n == 0) return null
        return when (line[0].toInt()) {
            't'.code -> parseFrame(n, idDigits = 3, extended = false)   // tIIILDD..
            'T'.code -> parseFrame(n, idDigits = 8, extended = true)    // TIIIIIIIILDD..
            else -> null   // 'r'/'R' remote frames, 'z'/'Z' tx echoes — ignorés
        }
    }

    private fun parseFrame(n: Int, idDigits: Int, extended: Boolean): CanFrame? {
        val dlcPos = 1 + idDigits
        if (n < dlcPos + 1) return null
        var id = 0
        for (i in 1..idDigits) {
            val v = hex(line[i]); if (v < 0) return null
            id = (id shl 4) or v
        }
        val dlc = hex(line[dlcPos])
        if (dlc !in 0..8) return null  // CAN 2.0 classique : DLC ∈ [0,8]
        if (n < dlcPos + 1 + dlc * 2) return null
        val data = ByteArray(dlc)
        for (i in 0 until dlc) {
            val hi = hex(line[dlcPos + 1 + i * 2])
            val lo = hex(line[dlcPos + 2 + i * 2])
            if (hi < 0 || lo < 0) return null
            data[i] = ((hi shl 4) or lo).toByte()
        }
        return CanFrame(id = id, data = data, extended = extended)
    }

    /** Valeur d'un chiffre hexadécimal ASCII, -1 si invalide. */
    private fun hex(b: Byte): Int = when (val c = b.toInt()) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> -1
    }

    companion object {
        private const val TAG = "SlcanParser"
        /** Frame extended max = `T<8 hex id><1 hex dlc><8×2 hex data>\r` = 26 chars. */
        private const val MAX_LINE_LEN = 32

        /** Encode une frame en ligne ASCII slcan TX. */
        fun encodeFrame(id: Int, data: ByteArray, extended: Boolean = false): String {
            val sb = StringBuilder()
            sb.append(if (extended) 'T' else 't')
            if (extended) sb.append("%08X".format(id))
            else sb.append("%03X".format(id and 0x7FF))
            sb.append(data.size.toString(16).uppercase())
            for (b in data) sb.append("%02X".format(b.toInt() and 0xFF))
            sb.append('\r')
            return sb.toString()
        }
    }
}
