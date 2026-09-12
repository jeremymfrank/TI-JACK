import com.tijack.evo.Kermit

fun main() {
    val session = Kermit.Session()
    val packet = Kermit.makePacket(
        0,
        'S',
        Kermit.sendInit,
        session
    )
    val expectedSendInitPacket =
        "013020537e3020402d2359317e2e22354d3e0d"
    check(
        packet.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        } == expectedSendInitPacket
    )

    val decoded = Kermit.parsePacket(packet, session)
    check(decoded.sequence == 0)
    check(decoded.type == 'S')
    check(decoded.data.contentEquals(Kermit.sendInit))

    val attrs =
        Kermit.fileAttribute('"', "B8") +
        Kermit.fileAttribute('1', "1") +
        Kermit.fileAttribute('@')
    check(
        attrs.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        } == "222242383121314020"
    )

    println("TI-JACK Evo Kermit self-test passed")
}
