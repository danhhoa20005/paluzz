# AppTVBox — Cầu nối máy chấm công cho Android TV Box

Phiên bản Android TV Box (Kotlin + Jetpack Compose) chuyển thể hoàn chỉnh từ ứng dụng Windows WinForms (`AppMayTinh`), thay thế **100% thư viện Windows 32-bit DLL (`FKAttend.dll`, `FKViaDev.dll`, `LFWViaDev.dll`, `FKPwdEncDec.dll`)** bằng tầng giao thức TCP nhị phân viết thuần Kotlin.

---

## 1. Kiến trúc Clean Architecture + MVVM

```
app/src/main/java/com/edusing/chamcongtvbox/
├── domain/                     # Pure Kotlin — KHÔNG phụ thuộc Android SDK hay thư viện mạng
│   ├── model/                  # BrandingConfig, DeviceConfig (TCP/UDP, License 5912), AttendanceLog, Member...
│   ├── protocol/               # FkDeviceGateway (interface), FkModels (18 dòng firmware & mã lỗi)
│   ├── repository/             # ConfigRepository, AuthRepository, AttendanceRepository, MemberRepository...
│   └── usecase/                # 14 UseCases (Login, DiscoverDevices, PollAttendance, PushMembers, AbbreviateNames...)
├── data/                       # Tầng cài đặt cụ thể (mỗi file ngắn gọn <= 250 dòng)
│   ├── protocol/               # FkByteChannel (TCP/UDP), FkViaDevTransport, LfwViaDevTransport, FkPwdCipher, FkDeviceInfoParser, FkAttendanceLogParser, FkUserEnrollParser, FkUserNameCodec, FkSession, FkDeviceGatewayImpl
│   ├── remote/                 # AttendanceApiClient (OkHttp), ApiResponseParser, ServerTimeParser (UTC+7)
│   ├── local/                  # SecureConfigStore (AES-256), WordDocExporter (.doc có BOM UTF-8)
│   ├── network/                # LanScannerImpl (quét LAN bằng Ping an toàn, lọc bỏ VPN/Tailscale)
│   └── repository/             # RepositoryImpls
├── service/                    # Vận hành 24/7 trên TV Box
│   ├── AttendanceEngine.kt     # Bộ điều phối chính (phối hợp EngineStateStore + DeviceConnectionManager + AttendanceLoopRunner)
│   ├── AttendanceForegroundService.kt  # ForegroundService + PartialWakeLock + tự nối lại khi mạng phục hồi
│   └── BootReceiver.kt         # Tự khởi động lại khi TV Box có điện sau cúp điện
├── di/                         # AppContainer (Composition Root) + AppViewModelFactory
└── presentation/               # MVVM (ViewModel + StateFlow/SharedFlow + Jetpack Compose TV UI)
    ├── theme/                  # Bảng màu đồng bộ AppMayTinh + viền focus vàng cho D-Pad điều khiển TV
    ├── components/             # TvButtons (TvActionButton, StatusPill, TvSubScreenHeader), TvDialogs (TvSafeConfirmDialog mặc định focus KHÔNG)
    ├── login/                  # LoginScreen (kèm con mắt 👁 hiện/ẩn mật khẩu) + SavedAccountsPane
    ├── main/                   # MainScreen (chế độ Màn khoá khi đang chạy, nút Dò máy, Cài đặt) + MainHeaderAndToolbar + MainPanels
    ├── adddevice/              # AddDeviceScreen + AddDeviceFormPane (đổi IP trống, TCP/UDP, nối thử) + AddDeviceLanPane
    ├── push/                   # PushMembersScreen + PushMembersListComponents (tìm không dấu, xem trước tên, xuất Word)
    ├── users/                  # DeviceUsersScreen + DeviceUsersTable (tìm không dấu, xoá đơn & hàng loạt)
    ├── settings/               # SettingsScreen (TCP/UDP, cổng, License 5912, chu kỳ quét, bỏ tối ưu pin Android)
    └── MainActivity.kt         # Điều hướng gốc + phím Back điều khiển TV + tự bắt đăng nhập lại khi 401/403
```

Chi tiết giao thức nhị phân xem tại [PROTOCOL.md](PROTOCOL.md).

---

## 2. Ba phiên bản thương hiệu (Product Flavors)

| Flavor | Application ID | Domain API mặc định | Header `x-brand` | Đối tượng |
|---|---|---|---|---|
| `diemdanh` | `com.edusing.chamcongtvbox.diemdanh` | `https://api.diemdanhhs.com` | `DIEMDANH` | Trường / Lớp / Học sinh |
| `paluzz` | `com.edusing.chamcongtvbox.paluzz` | `https://api.paluzz.com` | `PALUZZ` | Đơn vị / Phòng ban / Nhân sự |
| `dontra` | `com.edusing.chamcongtvbox.dontra` | `https://api.dontrahs.com` | `DONTRA` | Trường / Lớp / Học sinh (Đón - Trả) |

---

## 3. Lệnh Build & Chạy Kiểm Thử

Yêu cầu: **JDK 17** và **Android SDK** (compileSdk 36, minSdk 24 / Android 7.0+).

```bash
# Chạy toàn bộ Unit Tests (bao gồm test giao thức TCP loopback giả lập máy chấm công thật)
./gradlew testDiemdanhDebugUnitTest testPaluzzDebugUnitTest testDontraDebugUnitTest

# Build APK cho cả 3 thương hiệu
./gradlew assembleDiemdanhDebug assemblePaluzzDebug assembleDontraDebug
```

File APK sau khi build nằm tại:
- `app/build/outputs/apk/diemdanh/debug/app-diemdanh-debug.apk`
- `app/build/outputs/apk/paluzz/debug/app-paluzz-debug.apk`
- `app/build/outputs/apk/dontra/debug/app-dontra-debug.apk`

---

## 4. Cài đặt lên Android TV Box

### Cách 1: Qua USB (không cần máy tính)
1. Copy file `.apk` tương ứng vào USB.
2. Cắm USB vào Android TV Box, mở trình quản lý tệp (File Manager) và cài đặt file `.apk` (cho phép cài từ nguồn không xác định nếu Android hỏi).
3. Mở ứng dụng từ màn hình chính TV (hỗ trợ cả Android TV Launcher lẫn Android Box thường).

### Cách 2: Qua ADB trên cùng mạng LAN
```bash
adb connect <IP_TV_BOX>:5555
adb install -r app/build/outputs/apk/diemdanh/debug/app-diemdanh-debug.apk
```

### Khuyến nghị vận hành 24/7 trên TV Box
- Cắm dây mạng **LAN (Ethernet)** cho TV Box và đặt **IP tĩnh** cho máy chấm công cùng dải mạng.
- Trong Cài đặt Android TV Box -> Ứng dụng -> chọn app chấm công -> **Pin / Battery**: chuyển sang **Không tối ưu hoá (Unrestricted)** để dịch vụ nền chạy ổn định 24/7.
- Ứng dụng hỗ trợ thao tác hoàn toàn bằng **điều khiển TV (D-Pad)** hoặc **chuột/bàn phím USB**.
