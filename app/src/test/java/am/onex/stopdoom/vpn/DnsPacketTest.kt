package am.onex.stopdoom.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketTest {

    private val clientIp = DnsPacket.ipv4ToBytes("10.7.7.2")!!
    private val dnsIp = DnsPacket.ipv4ToBytes("10.7.7.3")!!

    /** A minimal DNS A query for [name]. */
    private fun dnsQuery(name: String, txId: Int = 0x1234): ByteArray {
        val labels = name.split('.')
        val body = ArrayList<Byte>()
        // header
        body.add((txId ushr 8).toByte()); body.add((txId and 0xFF).toByte())
        body.add(0x01); body.add(0x00)   // standard query, recursion desired
        body.add(0x00); body.add(0x01)   // QDCOUNT 1
        repeat(6) { body.add(0x00) }     // AN/NS/AR counts
        // question
        labels.forEach { label ->
            body.add(label.length.toByte())
            label.forEach { body.add(it.code.toByte()) }
        }
        body.add(0x00)                   // root label
        body.add(0x00); body.add(0x01)   // QTYPE A
        body.add(0x00); body.add(0x01)   // QCLASS IN
        return body.toByteArray()
    }

    @Test
    fun `round trips an ipv4 udp datagram`() {
        val payload = dnsQuery("example.com")
        val packet = DnsPacket.buildIpv4Udp(clientIp, dnsIp, 51000, 53, payload)

        val parsed = DnsPacket.parseIpv4Udp(packet)
        assertNotNull(parsed)
        assertArrayEquals(clientIp, parsed!!.srcIp)
        assertArrayEquals(dnsIp, parsed.dstIp)
        assertEquals(51000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `ip header checksum is valid`() {
        val packet = DnsPacket.buildIpv4Udp(clientIp, dnsIp, 51000, 53, dnsQuery("a.com"))
        // Summing a correct header including its checksum yields 0xFFFF.
        var sum = 0L
        for (i in 0 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFFL, sum)
    }

    @Test
    fun `reads the question name`() {
        assertEquals("example.com", DnsPacket.questionName(dnsQuery("example.com")))
        assertEquals("a.b.c.example.co.uk", DnsPacket.questionName(dnsQuery("a.b.c.example.co.uk")))
    }

    @Test
    fun `question name is lowercased`() {
        assertEquals("example.com", DnsPacket.questionName(dnsQuery("EXAMPLE.COM")))
    }

    @Test
    fun `builds an nxdomain reply that echoes the question`() {
        val query = dnsQuery("pornhub.com", txId = 0xBEEF)
        val reply = DnsPacket.buildNxDomain(query)
        assertNotNull(reply)
        reply!!

        assertEquals(0xBEEF, DnsPacket.transactionId(reply))
        // QR bit set: this is a response, not a query.
        assertTrue((reply[2].toInt() and 0x80) != 0)
        // RCODE 3 = name error.
        assertEquals(3, reply[3].toInt() and 0x0F)
        // No answers of any kind.
        assertEquals(0, ((reply[6].toInt() and 0xFF) shl 8) or (reply[7].toInt() and 0xFF))
        assertEquals(0, ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF))
        assertEquals(0, ((reply[10].toInt() and 0xFF) shl 8) or (reply[11].toInt() and 0xFF))
        // Question is echoed back unchanged, which resolvers require.
        assertEquals("pornhub.com", DnsPacket.questionName(reply))
    }

    @Test
    fun `recursion desired is preserved in the reply`() {
        val reply = DnsPacket.buildNxDomain(dnsQuery("x.com"))!!
        assertEquals(1, reply[2].toInt() and 0x01)
    }

    // --- malformed input: none of these may throw ---------------------------

    @Test
    fun `rejects truncated packets`() {
        assertNull(DnsPacket.parseIpv4Udp(ByteArray(4)))
        assertNull(DnsPacket.parseIpv4Udp(ByteArray(20)))
    }

    @Test
    fun `rejects non ipv4`() {
        val packet = DnsPacket.buildIpv4Udp(clientIp, dnsIp, 1, 53, dnsQuery("a.com"))
        packet[0] = 0x65 // version 6
        assertNull(DnsPacket.parseIpv4Udp(packet))
    }

    @Test
    fun `rejects non udp`() {
        val packet = DnsPacket.buildIpv4Udp(clientIp, dnsIp, 1, 53, dnsQuery("a.com"))
        packet[9] = 6 // TCP
        assertNull(DnsPacket.parseIpv4Udp(packet))
    }

    @Test
    fun `question name handles empty and short input`() {
        assertNull(DnsPacket.questionName(ByteArray(0)))
        assertNull(DnsPacket.questionName(ByteArray(11)))
    }

    @Test
    fun `question name refuses a compression pointer`() {
        val query = dnsQuery("example.com")
        query[12] = 0xC0.toByte() // pointer where a length byte belongs
        assertNull(DnsPacket.questionName(query))
    }

    @Test
    fun `question name refuses a label running past the end`() {
        val query = dnsQuery("example.com")
        query[12] = 0x50 // claims an 80-byte label
        assertNull(DnsPacket.questionName(query))
    }

    @Test
    fun `question name returns null when there are no questions`() {
        val query = dnsQuery("example.com")
        query[4] = 0; query[5] = 0 // QDCOUNT 0
        assertNull(DnsPacket.questionName(query))
    }

    @Test
    fun `nxdomain returns null for unparseable input`() {
        assertNull(DnsPacket.buildNxDomain(ByteArray(3)))
    }

    @Test
    fun `parses ipv4 text addresses and rejects junk`() {
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), DnsPacket.ipv4ToBytes("1.1.1.1"))
        assertArrayEquals(
            byteArrayOf(255.toByte(), 0, 0, 1),
            DnsPacket.ipv4ToBytes("255.0.0.1"),
        )
        assertNull(DnsPacket.ipv4ToBytes("1.1.1"))
        assertNull(DnsPacket.ipv4ToBytes("256.0.0.1"))
        assertNull(DnsPacket.ipv4ToBytes("not an ip"))
    }

    @Test
    fun `isQuery distinguishes queries from responses`() {
        val query = dnsQuery("a.com")
        assertTrue(DnsPacket.isQuery(query))
        assertTrue(!DnsPacket.isQuery(DnsPacket.buildNxDomain(query)!!))
    }
}
