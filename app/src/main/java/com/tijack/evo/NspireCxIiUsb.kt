package com.tijack.evo

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

/**
 * USB identity/descriptor helpers for the TI-Nspire CX II / CX II CAS probe.
 *
 * This is intentionally not a transfer implementation. It gives the existing
 * TI-JACK diagnostic screen a precise CX II target while the session and
 * filesystem protocol are developed independently.
 */
internal object NspireCxIiUsb {
    const val TI_VID = 0x0451
    const val CXII_PID = 0xE022

    fun matches(device: UsbDevice): Boolean =
        device.vendorId == TI_VID && device.productId == CXII_PID

    fun describe(device: UsbDevice): String = buildString {
        append("TI-NSPIRE CX II PROBE\n")
        append("VID:PID ")
            .append("%04X:%04X".format(device.vendorId, device.productId))
            .append('\n')
        append("INTERFACES ").append(device.interfaceCount).append('\n')

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            append("  IF #").append(i)
                .append(" CLASS ").append(intf.interfaceClass)
                .append(" SUB ").append(intf.interfaceSubclass)
                .append(" PROTO ").append(intf.interfaceProtocol)
                .append(" ENDPOINTS ").append(intf.endpointCount)
                .append('\n')

            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                append("    EP #").append(e)
                    .append(" ADDR 0x").append("%02X".format(ep.address))
                    .append(' ')
                    .append(if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT")
                    .append(' ')
                    .append(endpointType(ep.type))
                    .append(" MAX ").append(ep.maxPacketSize)
                    .append(" INTERVAL ").append(ep.interval)
                    .append('\n')
            }
        }
    }

    private fun endpointType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISO"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        else -> "TYPE$type"
    }
}
