# Giao thức TCP nhị phân FKAttend (Pure Kotlin)

Tài liệu này ghi lại đặc tả gói tin nhị phân ở tầng TCP (mặc định cổng `5005`) được dịch ngược trực tiếp từ 4 thư viện Windows 32-bit x86 gốc:
- `FKViaDev.dll` (giao thức truyền tải chuẩn cho các dòng máy CIF10..CIF15_1, GEN, PS, PS_EXCEL)
- `LFWViaDev.dll` (giao thức truyền tải Linux Firmware — `CFKAttend_LFW`)
- `FKAttend.dll` (18 lớp firmware RTTI: `CFKAttend_CIF10`..`CIF15_1`, `GEN`, `PS`, `PS_EXCEL`, `LFW`)
- `FKPwdEncDec.dll` (thuật toán mã hoá mật khẩu/số thẻ `FKHS3760`)

Toàn bộ được viết lại **100% bằng Kotlin thuần (`java.net.Socket`)**, không cần DLL Windows, chạy native trên Android TV Box (ARM32, ARM64, x86).

---

## 1. Tầng truyền tải (Transport Framing)

### 1.1. `FKViaDev` (`FkViaDevTransport.kt`) — 16B Request / 10B Response (không checksum)

**Request Header (16 bytes, Little-Endian):**

| Offset | Kích thước | Trường | Giá trị |
|---|---|---|---|
| `0..1` | 2B | Magic | `0x55`, `0xAA` |
| `2` | 1B | `machineNo` | Số máy (`1..255`) |
| `3` | 1B | `cmd` | Mã lệnh (opcode) |
| `4..7` | 4B u32 LE | `param` | Tham số chính |
| `8..9` | 2B u16 LE | `inParam16` | Tham số phụ |
| `10..11` | 2B u16 LE | `blockIndex` | `0xFFFF` cho `SendCommand`; `0..N-1` cho `ReadData`/`WriteData` |
| `12..13` | 2B u16 LE | `blockSize` | `0` cho `SendCommand`; kích thước chunk (`<= 1024`, hoặc `<= 256` nếu `commVersion == 0x103`) |
| `14..15` | 2B u16 LE | `seqNo` | Bộ đếm tuần tự `1..65535` (bỏ qua `0`) |

Nếu là `WriteData` (`blockSize > 0`), nối ngay sau header:
`[0x55, 0xAA] + data[blockSize] + [0x00, 0x00]`.

**Response Header (10 bytes):**

Quét luồng nhận tìm `[i] == 0xAA`, `[i+1] == 0x55` sao cho `u16LE[i+8..9] == 0 || == seqNo`:

| Offset | Kích thước | Trường | Giá trị |
|---|---|---|---|
| `0..1` | 2B | Magic | `0xAA`, `0x55` |
| `2` | 1B | `machineNo` | Số máy |
| `3` | 1B | `status` | `0x01` = OK (`1`), `0xFD` = lỗi giao tiếp (`-3`), khác = lỗi thiết bị (`-6`) |
| `4..7` | 4B u32 LE | `outParam` | Giá trị trả về (ví dụ: tổng số log, số user) |
| `8..9` | 2B u16 LE | `seqNo` | Số thứ tự khớp yêu cầu (hoặc `0`) |

Nếu là `ReadData` (`status == 0x01` và `expectedLen > 0`), nối sau header:
`[0x55, 0xAA] + data[expectedLen] + [2 byte đệm]`.

---

### 1.2. `LFWViaDev` (`LfwViaDevTransport.kt`) — 20B Request / 16B Response (có checksum 16-bit)

Tự động dùng làm fallback nếu bắt tay `FKViaDev` thất bại.

**Request Header (20 bytes):**

| Offset | Kích thước | Trường |
|---|---|---|
| `0..1` | 2B | `0x55`, `0xAA` |
| `2` | 1B | `machineNo` |
| `3` | 1B | `cmd` |
| `4..7` | 4B u32 LE | `param` |
| `8..11` | 4B u32 LE | `inParam32` |
| `12..13` | 2B u16 LE | `blockIndex` (`0xFFFF` cho `SendCommand`) |
| `14..15` | 2B u16 LE | `blockSize` |
| `16..17` | 2B u16 LE | `checksum` = tổng unsigned byte của `header[0..19]` (với `[16..17] = 0`) mod 65536 |
| `18..19` | 2B u16 LE | `seqNo` (`1..65535`) |

**Response Header (16 bytes):**

| Offset | Kích thước | Trường |
|---|---|---|
| `0..1` | 2B | `0xAA`, `0x55` |
| `2` | 1B | `machineNo` |
| `3` | 1B | `status` (`0x01` = OK, `0xFD` = `-3`, khác = `-6`) |
| `4..7` | 4B u32 LE | `outParam` |
| `8..9` | 2B | Reserved |
| `10..11` | 2B u16 LE | `checksum` = tổng unsigned byte của `header[0..15]` (với `[10..11] = 0`) mod 65536 |
| `12..15` | 4B u32 LE | `seqNo` |

---

## 2. Quy trình bắt tay kết nối (Handshake — `FkDeviceGatewayImpl.kt`)

1. Mở socket TCP (`tcpNoDelay = true`) hoặc UDP (`DatagramSocket` khi bật `UDP_MODE`) tới `ip:port` qua lớp trừu tượng `FkByteChannel.kt`.
2. Gửi `CMD_CONNECT_AUTH (0x80)` với `param = commPassword` (mặc định `0`).
3. Đọc 48 bytes DeviceInfo bằng `CMD_GET_DEVICE_INFO (0x13)` (`param = 0`, `inParam = 0`):
   - `diType = u16LE[0]`, `modelFlag = u16LE[4] & 0x0F`, `license = u32LE[8]` (mặc định `5912` khớp `ConfigStore.cs`, tự chấp nhận `0` hoặc `1261`), `commVersion = u16LE[12]`, `logType = u8[15]`, `subType = u8[16]`, `extInfoLen = u16LE[46]`.
   - Nếu `commVersion == 0x103`, hạ `maxBlockSize` từ `1024` xuống `512` (khớp `FKAttend.dll @ 0x1000b638`).
   - Nếu `diType >= 0x104` và `extInfoLen in 1..2048`: gửi thêm `CMD_GET_DEVICE_INFO (0x13)` với `param = 1` để đọc chuỗi JSON chứa `"CommunicationInterfaceId"` (`"10"`, `"10_1"`, `"11"`, `"11_1"`, `"11_2"`, `"12"`, `"12_1"`, `"12_2"`, `"14"`, `"14_1"`, `"15"`, `"15_1"`).
   - Nếu không có JSON mở rộng: `modelFlag == 3 || 4` -> `PS_EXCEL` (nếu `subType == 2`) hoặc `PS`; ngược lại -> `GEN`.
4. Nếu `FKViaDev` thất bại, mở lại kết nối bằng `LFWViaDev`, gửi `0x80`, đọc 54 bytes DeviceInfo bằng `0x13` -> chọn profile `LFW`.
5. `FK_DisConnect` không có opcode trên đường dây — chỉ đóng socket TCP (shutdown + close).

---

## 3. Bảng Opcode chuẩn đã kiểm chứng

| Chức năng | Opcode | Tham số | Ghi chú |
|---|---|---|---|
| Xác thực kết nối | `0x80` | `param = password` | |
| Đọc DeviceInfo / ExtInfo | `0x13` | `param = 0` (48B/54B) hoặc `param = 1` (JSON) | |
| Khóa / mở máy | `0x81` | `param = 0` (khoá) / `1` (mở) | Dùng trước/sau ghi thẻ & xoá người |
| Đọc trạng thái (đếm log/user) | `0xB4` | `param = statusIndex` | LFW ánh xạ lại index theo bảng mục 3.1 |
| Đọc log chưa đọc | `0xA1` | `totalLen = count * recSize` | Sau đó gọi `0xA2 (param=2)` đánh dấu đã đọc |
| Đánh dấu log đã đọc | `0xA2` | `param = 2` | |
| Đọc toàn bộ log | `0xA4` | `totalLen = count * recSize` | |
| Xoá sạch bộ nhớ log | `0xA8` | `param = 0` | Timeout 26s |
| Đọc toàn bộ User ID | `0x97` | `param = totalBytes` | 8B/user (`CIF15/15_1`: 10B/user) |
| Ghi dữ liệu thẻ (`backup=11`) | `0x98` (LFW: `0x9B`) | `param = 11` (LFW: `param=enrollNo, inParam=11`) | Kích thước 12B/16B/56B/64B/88B tuỳ profile |
| Lưu dữ liệu vào Flash | `0x92` | `param = 1` | Timeout 60s |
| Xoá dữ liệu người dùng | `0x93` | `param = enrollNo, inParam = backupNo (0..12)` | |
| Đọc / ghi tên người dùng | `0xC7`/`0xC8` hoặc `0xE8`/`0xE9` (LFW: `0x98`/`0x99`) | UTF-16LE (hoặc ASCII trên PS/GEN) | `CIF11_1/11_2/15`: 14B UTF-16LE = tối đa 6 ký tự |

### 3.1. Bảng ánh xạ `GetDeviceStatus` trên LFW
`{1->2, 2->3, 3->4, 4->6, 5->9, 6->8, 7->11, 8->10, 9->7, 10->5}` (nhờ vậy `statusIndex = 6` [số log chưa đọc] được gửi là `8` trên LFW).

---

## 4. Thuật toán mã hoá thẻ/mật khẩu `FKHS3760` (`FkPwdCipher.kt`)

Dịch ngược từ `FKPwdEncDec.dll` (`0x10001030` / `0x10001060`):
```kotlin
var state = 0x307F
// Encrypt:
val cipher = ((state ushr 8) xor plainByte) and 0xFF
state = (cipher + state) * 0x70E8 + 0x8BAC
// Decrypt:
val plain = ((state ushr 8) xor cipherByte) and 0xFF
state = (cipher + state) * 0x70E8 + 0x8BAC
```
Số thẻ dạng chuỗi ASCII (ví dụ `"12345678"`, đệm `0x00` đủ 32 byte cho `CIF11..15_1` hoặc 24 byte cho `CIF10`) được mã hoá bằng `encryptHs3760` trước khi đặt vào struct `PutEnrollData`.

---

## 5. Lưu ý phần cứng thực tế
1. **Tuyệt đối không quét cổng TCP `5005` hàng loạt**: Máy chấm công FK chỉ chịu được 1 phiên TCP đồng thời; kết nối mở-đóng đột ngột khi scan cổng làm firmware treo ~2 phút. Do đó `LanScannerImpl.kt` luôn quét bằng **ICMP Ping** trước.
2. **Đợi ~1 giây trước khi reconnect**: Sau khi đóng socket TCP, chờ tối thiểu `1000ms` trước khi kết nối lại để firmware kịp giải phóng socket.
3. **Mỗi thiết bị dùng 1 `Mutex` riêng** trong `FkSession` để tuần tự hoá mọi lệnh đọc/ghi.
4. **Giới hạn tên hiển thị 6 byte (`MAX_TEN_BYTE = 6`)**: Trên các máy phổ biến như EDS3000-TWM (`CIF11_1`), bộ đệm `0xC8` dài đúng 14 bytes UTF-16LE (tối đa 6 ký tự + 2 byte `NUL`). `AbbreviateNamesUseCase` tự động rút gọn tiếng Việt không dấu (VD: `"Nguyễn Thị Phương"` -> `"NT.P"`) và tự đánh số chống trùng (`NT.P2`, `NT.P3`...).
