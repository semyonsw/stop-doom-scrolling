package am.onex.stopdoom.vpn

/**
 * Just enough IPv4/UDP/DNS to read a query off a tun interface and answer it.
 *
 * Only what the tunnel actually carries is implemented: the VPN routes a single
 * fake IPv4 DNS address, so there is no IPv6 path and no TCP path by construction.
 * Anything unexpected returns null and gets forwarded untouched rather than guessed at.
 */
object DnsPacket {

    const val PROTOCOL_UDP = 17
    private const val IPV4_MIN_HEADER = 20
    private const val UDP_HEADER = 8
    private const val DNS_HEADER = 12
    private const val MAX_NAME_LENGTH = 255

    class Datagram(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    fun parseIpv4Udp(packet: ByteArray, length: Int = packet.size): Datagram? {
        if (length < IPV4_MIN_HEADER) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        if (versionAndIhl ushr 4 != 4) return null

        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < IPV4_MIN_HEADER || length < headerLength + UDP_HEADER) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        // A fragmented DNS query is not a thing worth handling; forward it instead.
        val fragmentField = readUShort(packet, 6)
        if (fragmentField and 0x1FFF != 0) return null

        val totalLength = readUShort(packet, 2).coerceAtMost(length)
        val udpOffset = headerLength
        val udpLength = readUShort(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER) return null

        val payloadLength = (udpLength - UDP_HEADER)
            .coerceAtMost(totalLength - udpOffset - UDP_HEADER)
        if (payloadLength < 0) return null

        return Datagram(
            srcIp = packet.copyOfRange(12, 16),
            dstIp = packet.copyOfRange(16, 20),
            srcPort = readUShort(packet, udpOffset),
            dstPort = readUShort(packet, udpOffset + 2),
            payload = packet.copyOfRange(
                udpOffset + UDP_HEADER,
                udpOffset + UDP_HEADER + payloadLength,
            ),
        )
    }

    fun buildIpv4Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val total = IPV4_MIN_HEADER + UDP_HEADER + payload.size
        val out = ByteArray(total)

        out[0] = 0x45                       // IPv4, 5 x 32-bit words of header
        out[1] = 0                          // DSCP/ECN
        writeUShort(out, 2, total)
        writeUShort(out, 4, 0)              // identification
        writeUShort(out, 6, 0x4000)         // don't fragment
        out[8] = 64                         // TTL
        out[9] = PROTOCOL_UDP.toByte()
        writeUShort(out, 10, 0)             // checksum placeholder
        srcIp.copyInto(out, 12, 0, 4)
        dstIp.copyInto(out, 16, 0, 4)
        writeUShort(out, 10, checksum16(out, 0, IPV4_MIN_HEADER))

        val udp = IPV4_MIN_HEADER
        writeUShort(out, udp, srcPort)
        writeUShort(out, udp + 2, dstPort)
        writeUShort(out, udp + 4, UDP_HEADER + payload.size)
        // Zero means "no checksum", which IPv4 permits and every resolver accepts.
        // Computing it would need a pseudo-header for no practical gain here.
        writeUShort(out, udp + 6, 0)
        payload.copyInto(out, udp + UDP_HEADER)

        return out
    }

    fun transactionId(dns: ByteArray): Int? =
        if (dns.size >= 2) readUShort(dns, 0) else null

    fun isQuery(dns: ByteArray): Boolean =
        dns.size >= DNS_HEADER && (dns[2].toInt() and 0x80) == 0

    /**
     * The QNAME of the first question, lowercased and without a trailing dot.
     * Returns null for malformed input rather than throwing - this runs on every
     * packet and a crash here would take the tunnel down.
     */
    fun questionName(dns: ByteArray): String? {
        if (dns.size < DNS_HEADER) return null
        if (readUShort(dns, 4) < 1) return null

        val name = StringBuilder()
        var offset = DNS_HEADER
        var guard = 0
        while (offset < dns.size) {
            if (guard++ > MAX_NAME_LENGTH) return null
            val len = dns[offset].toInt() and 0xFF
            when {
                len == 0 -> return name.toString().takeIf { it.isNotEmpty() }?.lowercase()
                // Compression pointers are legal in responses, not in a question we
                // are the first to read. Refuse rather than follow one.
                len and 0xC0 != 0 -> return null
                else -> {
                    val start = offset + 1
                    val end = start + len
                    if (end > dns.size || name.length + len > MAX_NAME_LENGTH) return null
                    if (name.isNotEmpty()) name.append('.')
                    name.append(String(dns, start, len, Charsets.US_ASCII))
                    offset = end
                }
            }
        }
        return null
    }

    /** Length of the question section, needed to echo it back in a reply. */
    private fun questionSectionEnd(dns: ByteArray): Int? {
        var offset = DNS_HEADER
        var guard = 0
        while (offset < dns.size) {
            if (guard++ > MAX_NAME_LENGTH) return null
            val len = dns[offset].toInt() and 0xFF
            if (len and 0xC0 != 0) return null
            offset += 1 + len
            if (len == 0) break
        }
        // QTYPE + QCLASS
        val end = offset + 4
        return if (end <= dns.size) end else null
    }

    /**
     * An NXDOMAIN reply to [query], echoing its header and question.
     *
     * NXDOMAIN rather than an A record pointing at 0.0.0.0: browsers fail fast and
     * clearly on it, instead of hanging while they try to connect to a dead address.
     */
    fun buildNxDomain(query: ByteArray): ByteArray? {
        val end = questionSectionEnd(query) ?: return null
        val out = query.copyOfRange(0, end)

        // QR=1 response, RD copied from the query, RA=1 recursion available.
        val rd = out[2].toInt() and 0x01
        out[2] = (0x80 or rd).toByte()
        // RA=1, RCODE=3 (name error).
        out[3] = (0x80 or 0x03).toByte()
        writeUShort(out, 6, 0)  // ANCOUNT
        writeUShort(out, 8, 0)  // NSCOUNT
        writeUShort(out, 10, 0) // ARCOUNT
        return out
    }

    fun ipv4ToBytes(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            out[i] = value.toByte()
        }
        return out
    }

    private fun readUShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun writeUShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun checksum16(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += readUShort(data, i).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
