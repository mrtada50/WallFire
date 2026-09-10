package com.example.netguardlite.vpn

/**
 * محلل بسيط لرأس حزمة IPv4 يستخرج البروتوكول والمنافذ والعناوين فقط
 * (يكفينا لمعرفة UID صاحب الاتصال، بدون فك أي محتوى فعلي للحزمة).
 */
object PacketParser {

    data class ParsedPacket(
        val protocol: Int, // 6 = TCP, 17 = UDP
        val sourceAddress: ByteArray,
        val destAddress: ByteArray,
        val sourcePort: Int,
        val destPort: Int
    )

    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null

        val versionAndIhl = buffer[0].toInt()
        val version = (versionAndIhl shr 4) and 0x0F
        if (version != 4) return null // نكتفي بـ IPv4 حالياً

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < 20 || length < ihl + 4) return null

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 6 && protocol != 17) return null // TCP أو UDP فقط

        val srcAddr = buffer.copyOfRange(12, 16)
        val dstAddr = buffer.copyOfRange(16, 20)

        val srcPort = ((buffer[ihl].toInt() and 0xFF) shl 8) or (buffer[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buffer[ihl + 2].toInt() and 0xFF) shl 8) or (buffer[ihl + 3].toInt() and 0xFF)

        return ParsedPacket(protocol, srcAddr, dstAddr, srcPort, dstPort)
    }
}
