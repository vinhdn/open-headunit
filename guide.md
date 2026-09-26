# USB permission không hiện popup trên headunit custom AOSP — ghi chú điều tra & fix

Ghi lại cho lần sau đọc lại: vì sao chọn thiết bị trong danh sách USB không hiện dialog
xin quyền trên head unit Android 9 custom AOSP (trong khi Pixel Android 12 hiện bình
thường), fix đã áp dụng, và cách test/tiếp tục nếu ROM khác biệt.

## Triệu chứng

- Cắm điện thoại → vào màn hình danh sách USB (`UsbListFragment`) → thiết bị hiện trong
  danh sách, nhưng khi chọn thì **không có popup xin quyền nào cả** (không phải bị từ
  chối — không có gì xảy ra).
- Trên Google Pixel (Android 12): dialog hiện bình thường, chọn Allow là kết nối được.

## Nguyên nhân

Đã đọc qua toàn bộ luồng (`UsbListFragment.kt` → `UsbLauncherManager.kt` →
`UsbReceiver.kt`) — **code của app không có bug**. Khi chọn thiết bị, app gọi đúng
chuẩn Android:

```kotlin
usbManager.requestPermission(device, pendingIntent)
```

Lệnh này **không tự vẽ dialog** — nó chỉ yêu cầu hệ thống (thường là một Activity nằm
trong SystemUI, ví dụ `UsbPermissionActivity`) khởi chạy dialog xác nhận. Khi người
dùng bấm Allow, hệ thống mới gửi broadcast báo `EXTRA_PERMISSION_GRANTED=true` về app.

Trên nhiều ROM AOSP custom cho ô tô (đặc biệt các ROM Android 9 của hãng làm đầu
DVD/Android Box Trung Quốc), SystemUI bị cắt gọt/tuỳ biến rất mạnh và **component xử lý
dialog này bị thiếu hoặc hỏng**. Kết quả: `requestPermission()` gọi xong không làm gì
cả — không dialog, không lỗi, không broadcast, mãi mãi treo ở đó.

> Lưu ý: luồng auto-attach (cắm dây, `UsbAttachedActivity` tự bắt qua
> `usb_device_filter.xml`) **không bị ảnh hưởng** — Android tự cấp quyền cho thiết bị
> khớp filter mà không cần dialog nào cả, đây là hành vi chuẩn từ lâu. Chỉ luồng
> **chọn thủ công trong danh sách** mới đi qua `requestPermission()` và bị ROM chặn.

## Fix đã áp dụng: fallback cấp quyền qua root

File mới:
- `app/src/main/java/com/andrerinas/openheadunit/connection/usb/UsbRootPermissionGranter.kt`
- `app/src/main/java/com/andrerinas/openheadunit/root/UsbGrantHelper.kt`

Cơ chế: sau khi gọi `requestPermission()` bình thường, app đợi 4 giây (đủ để dialog thật
— nếu có — kịp xuất hiện). Nếu vẫn chưa có quyền, và app đã có root/Shizuku (qua
`SUExecutor`), app sẽ:

1. Chạy 1 tiến trình `app_process` mang danh tính (UID) của root shell, gọi:
   ```
   apk=$(pm path <pkg> | grep base.apk | head -n1 | cut -d: -f2)
   CLASSPATH="$apk" app_process /system/bin com.andrerinas.openheadunit.root.UsbGrantHelperKt \
       <uid> <vendorId> <productId> <deviceName>
   ```
2. `UsbGrantHelperKt.main()` dùng reflection gọi thẳng
   `IUsbManager#grantDevicePermission(UsbDevice, uid)` — một hidden API mà bình thường
   cần permission `MANAGE_USB` (signature-level) mới gọi được. Vì tiến trình này chạy
   với UID 0 (root), mọi permission check kiểu `enforceCallingOrSelfPermission` trong
   framework đều coi UID 0 là đã được cấp sẵn — đúng lý do vì sao `su -c cmd ...` luôn
   vượt qua được các permission-protected system service.
3. Nếu thành công, app tự phát lại đúng broadcast mà dialog thật lẽ ra sẽ gửi
   (`ACTION_USB_DEVICE_PERMISSION` với `EXTRA_PERMISSION_GRANTED=true`), để toàn bộ
   logic kết nối phía sau chạy y hệt luồng bình thường.

**Không đụng `/system` hay ghi gì vĩnh viễn** — chỉ tác động tại runtime, hoàn toàn có
thể revert bằng cách không gọi lại / reboot. Chỉ kích hoạt khi root đã có sẵn qua
`SUExecutor` (không tự ý xin root nếu app chưa từng cần root).

Đây là tính năng **thử nghiệm**: phụ thuộc hidden API nội bộ Android
(`IUsbManager#getDeviceList(Bundle)`, `#grantDevicePermission`), vốn ổn định qua nhiều
version nhưng không có gì đảm bảo 100% trên mọi fork AOSP.

## Cách test trên máy thật

### 1. Xác nhận ROM có phải bản debug không

```
adb shell getprop ro.build.type       # userdebug / eng → su có thể mở cho mọi UID
adb shell getprop ro.debuggable       # 1
```

### 2. Test xem UID của app có gọi `su` thành công không (quyết định root fallback có chạy được không)

```
adb shell pm list packages | grep openheadunit
UID=$(adb shell su 0 sh -c "id -u com.andrerinas.openheadunit")
adb shell su "$UID" sh -c "su -c id"
```

- Ra `uid=0(root)` → app tự lấy được root, không cần thao tác gì thêm.
- `Permission denied` / không phản hồi → `su` trên ROM này chỉ cho UID shell (2000)
  dùng, root fallback trong app **không hoạt động được** ở dạng hiện tại → cần hướng
  khác (xem phần Shizuku / priv-app bên dưới).

### 3. Test end-to-end tính năng vừa thêm

```
adb logcat -s OPENHU:* SUExecutor:*
```

Cắm điện thoại, vào danh sách USB, chọn thiết bị không hiện popup, đợi ~4-5 giây. Log
mong đợi:

```
SU granted by Root                                  <- (hoặc "Root (Legacy)" / "Shizuku")
UsbRootPermissionGranter: ... attempting root grant
UsbRootPermissionGranter: helper exit=0 for ...
UsbRootPermissionGranter: root grant succeeded for ...
```

`SU not granted` nghĩa là root không lấy được cho app — quay lại bước 2 để biết vì sao.

## Nếu `su` không cấp được cho app UID: các hướng thay thế

### A. Shizuku (đã có sẵn khung trong `SUExecutor.ShizukuImpl`, nhưng **thiếu bước xin quyền chủ động**)

Code hiện tại chỉ gọi `Shizuku.checkSelfPermission()` (kiểm tra), **không có chỗ nào
gọi `Shizuku.requestPermission()`** để chủ động bật popup xin quyền của Shizuku. Cần bổ
sung nếu muốn dùng đường này — báo lại nếu muốn tôi làm phần đó.

Yêu cầu: cài app Shizuku (APK riêng), khởi động service qua `adb shell sh
.../start.sh` (không cần Magisk, chỉ cần adb hoạt động — đúng điều kiện máy bạn đang
có).

### B. Đưa app vào `/priv-app/` + xin permission `MANAGE_USB`

**Rủi ro cao hơn, chỉ nên thử nếu (A) và root-shell đều không được**, vì phải remount
`/system` (rw) — có thể fail nếu ROM bật dm-verity/AVB, và có thể brick nếu làm sai.

Từ Android 8 trở đi, priv-app **không tự động** có các permission `signature|privileged`
— còn cần khai báo whitelist tại
`/system/etc/permissions/privapp-permissions-<ten>.xml`. Thiếu file này:
- `ro.control_privapp_permissions=enforce` (phổ biến ROM mới) → **hệ thống tắt hẳn
  app lúc boot**.
- `disable`/`log` → âm thầm không cấp quyền, không lỗi rõ ràng.

Quan trọng hơn: chưa xác nhận được `android.permission.MANAGE_USB` có protectionLevel
`signature|privileged` (priv-app + whitelist là đủ) hay chỉ `signature` (bắt buộc ký
app bằng đúng platform key của ROM — thứ khó có được trừ khi tự build cả ROM từ
source). **Kiểm tra trước khi làm gì với `/system`** (lệnh này an toàn, chỉ đọc):

```
adb shell pm list permissions -f | grep -A3 "MANAGE_USB"
```

Xem dòng `protectionLevel:` — có chữ `privileged` thì (B) khả thi; chỉ `signature` thì
priv-app vô nghĩa với app này.

## Build release + keystore

- Keystore: `headunit-release-key.jks` (alias `headunit-revived`, validity 10.000
  ngày), mật khẩu trong `key.properties` — **cả hai đều gitignore, backup thủ công**,
  mất là mất khả năng update app này bằng đúng chữ ký.
- `key.properties` **không được đặt `storeFile`** — logic mặc định trong
  `app/build.gradle.kts` đã tự tìm `headunit-release-key.jks` ở root project; nếu đặt
  `storeFile=...` trong `key.properties`, đường dẫn đó bị resolve tương đối thư mục
  `app/` (không phải root) → APK build ra **không được ký mà không báo lỗi gì**. Đã bị
  dính lỗi này một lần, ghi lại để không lặp lại.
- Build lệnh: `./gradlew :app:assembleGithubRelease` (flavor `github`, không phải
  `playstore`). Yêu cầu `local.properties` có `sdk.dir=` (dùng forward slash `/`, dùng
  backslash `\` trong properties file sẽ bị Java Properties parser coi là escape
  character và làm sai đường dẫn).
- Verify chữ ký: `apksigner verify --print-certs <apk>`.
- Môi trường build trên máy: JBR mặc định của Android Studio ở đây là Java 25, và
  Gradle 8.13 (wrapper của project) **crash khi parse version "25.0.2"** lúc compile
  `build.gradle.kts`. Dùng JDK 21 khác làm `JAVA_HOME` (ví dụ bản Temurin 21 mà Gradle
  tự tải về `~/.gradle/jdks/`) để build.
