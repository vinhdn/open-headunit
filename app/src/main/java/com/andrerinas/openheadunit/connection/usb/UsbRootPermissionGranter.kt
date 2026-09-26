package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Process
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.SUExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Best-effort fallback for custom AOSP head units whose system USB permission confirmation UI
 * (normally shipped as part of SystemUI) is missing, disabled, or crashes: on those ROMs
 * [UsbManager.requestPermission] shows no dialog and never fires an allow/deny broadcast, so a
 * device picked from the USB list just sits there forever with no way to grant it. This has been
 * seen specifically on Android 9 head unit builds; the same flow works normally on stock/Pixel
 * ROMs, which is why this only engages once the real request has had a chance and failed.
 *
 * Root can grant the permission directly: [com.andrerinas.openheadunit.root.UsbGrantHelper] calls
 * the hidden IUsbManager#grantDevicePermission with the root shell's own calling identity, which
 * every AOSP permission check special-cases as already authorized (the same reason `su -c cmd
 * ...` works against permission-protected system services generally).
 *
 * This never writes anything under /system or /data - a wrong or failed attempt is undone by
 * simply not calling it again, or by a reboot. It also never requests root on its own; it only
 * asks [SUExecutor], which prompts (once) exactly like any other root-gated feature in the app.
 */
object UsbRootPermissionGranter {

    private const val HELPER_CLASS = "com.andrerinas.openheadunit.root.UsbGrantHelperKt"
    private const val DIALOG_GRACE_PERIOD_MS = 4000L

    /**
     * Call right after [UsbManager.requestPermission]. Does nothing until [DIALOG_GRACE_PERIOD_MS]
     * has passed with no answer, so a ROM whose dialog just takes a moment is never raced.
     */
    fun scheduleFallback(
        scope: CoroutineScope,
        context: Context,
        suExecutor: SUExecutor,
        usbManager: UsbManager,
        device: UsbDevice,
    ) {
        val deviceName = UsbDeviceCompat(device).uniqueName
        scope.launch {
            delay(DIALOG_GRACE_PERIOD_MS)
            if (usbManager.hasPermission(device)) return@launch

            // Root prompts need the main thread to actually show a dialog.
            val hasRoot = withContext(Dispatchers.Main) { suExecutor.checkPermission() }
            if (!hasRoot) {
                AppLog.i("UsbRootPermissionGranter: no root/Shizuku available; cannot force-grant $deviceName")
                return@launch
            }

            val granted = withContext(Dispatchers.IO) {
                attemptForceGrant(context, suExecutor, usbManager, device)
            }
            if (granted) {
                AppLog.i("UsbRootPermissionGranter: root grant succeeded for $deviceName")
                replayGrantBroadcast(context, device)
            } else {
                AppLog.w("UsbRootPermissionGranter: root grant attempt failed for $deviceName")
            }
        }
    }

    private suspend fun attemptForceGrant(
        context: Context,
        suExecutor: SUExecutor,
        usbManager: UsbManager,
        device: UsbDevice,
    ): Boolean {
        if (usbManager.hasPermission(device)) return true

        val uid = Process.myUid()
        val pkg = context.packageName
        // Picks the base APK (not a density/ABI split) so app_process has UsbGrantHelperKt on its
        // classpath, then runs it carrying the root shell's own UID into the Binder call.
        val cmd = "apk=\$(pm path $pkg | grep base.apk | head -n1 | cut -d: -f2); " +
            "if [ -z \"\$apk\" ]; then apk=\$(pm path $pkg | head -n1 | cut -d: -f2); fi; " +
            "CLASSPATH=\"\$apk\" app_process /system/bin $HELPER_CLASS " +
            "$uid ${device.vendorId} ${device.productId} '${device.deviceName}'"

        val exitCode = suExecutor.execShell(cmd, asRootUser = true)
        AppLog.i("UsbRootPermissionGranter: helper exit=$exitCode for ${UsbDeviceCompat(device).uniqueName}")

        // hasPermission() only reflects the other process's grant once it lands in this app's
        // cached Binder state, so give it a moment rather than checking exactly once.
        repeat(5) {
            if (usbManager.hasPermission(device)) return true
            delay(200)
        }
        return usbManager.hasPermission(device)
    }

    /** Re-fires the same "permission granted" event the real system dialog would have sent. */
    private fun replayGrantBroadcast(context: Context, device: UsbDevice) {
        val intent = Intent(UsbReceiver.ACTION_USB_DEVICE_PERMISSION).apply {
            setPackage(context.packageName)
            putExtra(UsbManager.EXTRA_DEVICE, device)
            putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true)
        }
        context.sendBroadcast(intent)
    }
}
