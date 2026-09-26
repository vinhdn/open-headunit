package com.andrerinas.openheadunit.root

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.IBinder
import kotlin.system.exitProcess

/**
 * Entry point for a standalone `app_process` invocation, not part of the app's normal process.
 * See [com.andrerinas.openheadunit.connection.usb.UsbRootPermissionGranter] for why this exists
 * and why running it this way (as the root shell's own UID, outside Zygote's per-app fork path)
 * clears IUsbManager's MANAGE_USB check without the app holding that signature permission.
 *
 * Kept out of the normal call graph - reached purely by class name from a shell command - so it
 * needs an explicit proguard `-keep` (see proguard-project.txt) or a release build strips it.
 *
 * Args: <uid> <vendorId> <productId> <deviceName>
 */
fun main(args: Array<String>) {
    try {
        if (args.size != 4) {
            System.err.println("usage: UsbGrantHelperKt <uid> <vendorId> <productId> <deviceName>")
            exitProcess(2)
        }
        val uid = args[0].toIntOrNull()
        val vendorId = args[1].toIntOrNull()
        val productId = args[2].toIntOrNull()
        val deviceName = args[3]
        if (uid == null || vendorId == null || productId == null) {
            System.err.println("UsbGrantHelperKt: bad args: ${args.joinToString(" ")}")
            exitProcess(2)
        }

        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "usb") as? IBinder
        if (binder == null) {
            System.err.println("UsbGrantHelperKt: ServiceManager.getService(\"usb\") returned null")
            exitProcess(3)
        }

        val usbService = Class.forName("android.hardware.usb.IUsbManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)

        val devices = Bundle()
        usbService.javaClass
            .getMethod("getDeviceList", Bundle::class.java)
            .invoke(usbService, devices)

        var target: UsbDevice? = null
        for (key in devices.keySet()) {
            @Suppress("DEPRECATION")
            val candidate = devices.getParcelable<UsbDevice>(key) ?: continue
            if (candidate.deviceName == deviceName) {
                target = candidate
                break
            }
            if (target == null && candidate.vendorId == vendorId && candidate.productId == productId) {
                target = candidate
            }
        }

        if (target == null) {
            System.err.println(
                "UsbGrantHelperKt: no attached device matched name=$deviceName vid=$vendorId pid=$productId " +
                    "(seen: ${devices.keySet().joinToString(",")})"
            )
            exitProcess(4)
        }

        usbService.javaClass
            .getMethod("grantDevicePermission", UsbDevice::class.java, Int::class.javaPrimitiveType)
            .invoke(usbService, target, uid)

        println("UsbGrantHelperKt: granted uid=$uid device=${target.deviceName}")
        exitProcess(0)
    } catch (t: Throwable) {
        System.err.println("UsbGrantHelperKt: failed: $t")
        t.printStackTrace()
        exitProcess(1)
    }
}
