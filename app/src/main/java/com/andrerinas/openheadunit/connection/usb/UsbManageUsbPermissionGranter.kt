package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Process
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Grants USB device permission directly when the app itself holds MANAGE_USB - true only when
 * installed as a privileged system app (under /system/priv-app) with MANAGE_USB whitelisted in a
 * privapp-permissions XML, since its protectionLevel is `signature|privileged` (confirmed via
 * `pm list permissions -f` on the affected head unit): the privileged half of that check is
 * satisfiable without the platform's own signing key.
 *
 * This is the same hidden IUsbManager#grantDevicePermission call
 * [com.andrerinas.openheadunit.root.UsbGrantHelper] makes through a root shell, just made
 * in-process: no subprocess, no su, because the OS now considers this app itself authorized to
 * call it directly. Checked first, before ever asking for the system dialog - when it works there
 * is nothing to show the user at all.
 */
object UsbManageUsbPermissionGranter {

    // Not android.Manifest.permission.MANAGE_USB - it's a hidden/system permission with no public
    // SDK constant to reference at compile time.
    private const val MANAGE_USB = "android.permission.MANAGE_USB"

    fun hasManageUsb(context: Context): Boolean =
        context.checkSelfPermission(MANAGE_USB) == PackageManager.PERMISSION_GRANTED

    /** A single local Binder call - fine to call from any thread. */
    fun grant(context: Context, usbManager: UsbManager, device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        if (!hasManageUsb(context)) return false

        val deviceName = UsbDeviceCompat(device).uniqueName
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "usb") as? IBinder
            if (binder == null) {
                AppLog.w("UsbManageUsbPermissionGranter: ServiceManager.getService(\"usb\") returned null")
                return false
            }

            val usbService = Class.forName("android.hardware.usb.IUsbManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)

            usbService.javaClass
                .getMethod("grantDevicePermission", UsbDevice::class.java, Int::class.javaPrimitiveType)
                .invoke(usbService, device, Process.myUid())

            val granted = usbManager.hasPermission(device)
            if (granted) {
                AppLog.i("UsbManageUsbPermissionGranter: granted via MANAGE_USB for $deviceName")
            } else {
                AppLog.w("UsbManageUsbPermissionGranter: grantDevicePermission call returned but hasPermission is still false for $deviceName")
            }
            granted
        } catch (e: Exception) {
            AppLog.w("UsbManageUsbPermissionGranter: grant failed for $deviceName: ${e.message}")
            false
        }
    }
}
