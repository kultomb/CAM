# AGENTS.md - Quy Tắc Vàng Kỹ Thuật & Kiến Trúc Studio Livestream Chuyên Nghiệp (Android 14/15)

> [!IMPORTANT]
> **TÀI LIỆU QUY TẮC CỐT LÕI CỦA DỰ ÁN (MANDATORY PROJECT RULES)**
> File này chứa toàn bộ quy tắc kỹ thuật và phác đồ kiến trúc tối ưu thu được sau khi hoàn thiện nhận dạng USB Video Capture (Sony A73 / MS2109) trên Android 14/15. Khi chỉnh sửa hoặc nâng cấp mã nguồn sau này, BẤT KỲ NGUYÊN TẮC NÀO TRONG ĐÂY CỦNG KHÔNG ĐƯỢC PHÉP VI PHẠM ĐỂ BẢO ĐẢM ỨNG DỤNG MÁT MÁY, KHÔNG LAG, KHÔNG CRASH VÀ KHÔNG RỚT LUỒNG.

---

## 1. Chuẩn Căn Chỉnh Bộ Nhớ 16KB (Android 14/15 16KB Page Alignment)

* **Vấn đề**: Kernel Android 14/15 bắt buộc kiểm tra căn chỉnh bộ nhớ 16KB (16KB Page Size). Các thư viện cũ (.so) biên dịch NDK cũ 4KB (`libuvc.so`, `libusb.so`, `libnativelib.so`) sẽ bị hệ điều hành chặn triệt để và hiện hộp thoại hệ thống *"Ứng dụng không tương thích với kích thước trang 16 KB"*.
* **Quy tắc**:
  * Tuyệt đối **KHÔNG** nhúng bất kỳ file `.so` 4KB legacy cũ nào vào `jniLibs`.
  * Toàn bộ mã nguồn Native C++ phải nằm trong `app/src/main/cpp/uvc_native.cpp` và được biên dịch trực tiếp từ NDK CMake với cờ bắt buộc trong [`CMakeLists.txt`](file:///c:/Users/CMD/Desktop/LIVE%20CAMERA/app/src/main/cpp/CMakeLists.txt):
    ```cmake
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
    ```

---

## 2. Giao Tiếp Kernel Trực Tiếp Qua USBDEVFS IOCTL (No JNA / No LibUsb)

* **Kiến trúc**:
  1. Java/Kotlin gọi `connection = usbManager.openDevice(device)` để lấy `fd = connection.fileDescriptor`.
  2. Truyền `fd` xuống Native C++ qua JNI `nativeStart(fd, epAddr, maxPacketSize, altSetting, surface)`.
  3. C++ gọi trực tiếp các lệnh Kernel IOCTL:
     * `ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifnum)` (Claim Interface 1)
     * `ioctl(fd, USBDEVFS_SETINTERFACE, &setif)` (Set AltSetting)
     * `ioctl(fd, USBDEVFS_SUBMITURB, &urb)` (Gửi Ring Buffer 8 URB Isochronous)
     * `ioctl(fd, USBDEVFS_REAPURBNDELAY, &reapedUrb)` (Đọc URB không khóa Main Thread)
     * `ioctl(fd, USBDEVFS_RELEASEINTERFACE, &ifnum)` (Nhả bus USB khi dừng stream)

---

## 3. Tránh Lỗi Treo Màn Hình Khởi Động (Non-Blocking IOCTL)

* **Vấn đề**: Lệnh `USBDEVFS_REAPURB` là lệnh khóa chặn (Blocking I/O). Khi luồng chính UI Thread gọi `workerThread.join()` để dừng camera, nó sẽ bị khóa cứng vĩnh viễn gây treo màn hình Splash Screen.
* **Quy tắc**:
  * Bắt buộc sử dụng lệnh **`USBDEVFS_REAPURBNDELAY`** (`0x8008550D`).
  * Nếu không có gói dữ liệu sẵn sàng, luồng C++ tạm nghỉ 2ms (`std::this_thread::sleep_for(std::chrono::milliseconds(2))`) để nhả CPU.

---

## 4. Nhận Dạng Động Endpoint & Packet Size (Dynamic USB Resolution)

* **Vấn đề**: Các đầu chuyển OTG USB 2.0 / USB 3.0 Hub hoặc điện thoại khác nhau gán địa chỉ Endpoint phần cứng khác nhau (`0x81`, `0x82` hoặc `0x83`) và `MaxPacketSize` (1024, 3072 hoặc 5120 Bytes). Gán cứng giá trị sẽ gây lỗi `EINVAL (-22)` trên Linux Kernel.
* **Quy tắc**:
  * Trong [`UvcOfficialEngine.kt`](file:///c:/Users/CMD/Desktop/LIVE%20CAMERA/app/src/main/java/com/hbg/live/capture/UvcOfficialEngine.kt), luôn tự động quét trích xuất `ep.address`, `ep.maxPacketSize` và `iface.alternateSetting` từ `UsbInterface` thực tế và truyền xuống C++.

---

## 5. An Toàn Tránh ANR IPC Binder Block

* **Vấn đề**: Gọi `device.productName` trên `UsbDevice` khi **chưa có quyền USB Permission** sẽ kích hoạt cuộc gọi Binder đồng bộ tới `UsbService` hệ thống và bị nghẽn >5 giây trên UI Thread, dẫn tới bảng thông báo ANR *"HBG LIVE CAMERA không phản hồi"*.
* **Quy tắc**:
  * Chỉ truy vấn `device.productName` khi `usbManager.hasPermission(device)` là `true`.
  * Nếu chưa có quyền, dùng chuỗi fallback an toàn `USB Capture (0xVID:0xPID)` trong 0.0001ms.

---

## 6. Khởi Tạo Duy Nhất 1 Lần Tránh Deadlock Camera2

* **Vấn đề**: `surfaceCreated()` và `requestRequiredPermissions()` cùng gọi `autoDetectAndSelectSource()` đồng thời 2 lần gây deadlock Binder Lock của `CameraService`.
* **Quy tắc**:
  * Dùng phương thức `initCameraAndAudioOnce()` kết hợp cờ bảo vệ `isSourceStarted` trong [`MainActivity.kt`](file:///c:/Users/CMD/Desktop/LIVE%20CAMERA/app/src/main/java/com/hbg/live/ui/MainActivity.kt) để đảm bảo camera chỉ được khởi tạo **đúng 1 lần duy nhất**.

---

## 7. Quy Tắc Lắp Ghép Khung Hình C++ MJPEG (FID Assembly Rule)

* **Vấn đề**: Cả 2 cờ `EOF` (End of Frame) và `FID` (Frame ID Flip) cùng kích hoạt gọi `deliverFrame()`. Lệnh gọi lặp 2 lần làm gửi mảng byte rỗng (0 bytes) sang Kotlin, làm `BitmapFactory.decodeByteArray()` trả về `null` gây đen màn hình.
* **Quy tắc Chuẩn**:
  * Chỉ kích hoạt `deliverFrame()` khi **cờ FID đảo chuyển đổi (`ctx->lastFid != currentFid`)** VÀ mảng dữ liệu hoàn chỉnh `frame.size() > 100` bắt đầu bằng hai byte marker JPEG `0xFF 0xD8`.
  * Tuyệt đối không gọi `deliverFrame()` 2 lần trên cùng một khung hình để tránh làm biến dạng hoặc vỡ ảnh.

---

## 8. Đường Xử Lý Low-Copy Pipeline (Tối Ưu Bộ Nhớ CPU & Zero GC)

* **Nguyên lý Đỉnh Cao**: Tránh tạo rác GC: `MJPEG -> Bitmap ARGB_8888 -> Canvas -> Bitmap -> MediaCodec` (ngốn 500MB/s RAM).
* **Sơ đồ Đường Xử Lý Low-Copy**:
  ```text
                      MJPEG (C++ UVC Engine)
                                │
                        ┌───────┴───────┐
                        ▼               ▼
                   Preview          Streaming
                        │               │
                        ▼               ▼
                   SurfaceView      MediaCodec
                   (ANativeWindow)  H.264 (Surface Input)
  ```
* **Quy tắc**:
  * Luồng USB C++ chỉ làm nhiệm vụ: `READ -> VALIDATE -> ASSEMBLE -> QUEUE`. Không bao giờ giải mã JPEG trong USB Reader thread.
  * Preview hiển thị qua `ANativeWindow` Low-Copy Pipeline.
  * Khung hình giải mã dùng chung tham chiếu (Shared Reference), không tạo bản sao lớn dư thừa.

---

## 9. Cách Ly Hoàn Toàn Luồng Mạng & USB Thread

* **Bài học**: Khi tín hiệu mạng 5G / WiFi bị suy giảm hoặc lag, bộ đẩy RTMP/SRT bị nghẽn. Nếu không cách ly, bộ nghẽn mạng sẽ kéo chậm luồng C++ USB Reader, làm mất gói URB Isochronous và gây sọc hình / rớt kết nối USB.
* **Quy tắc**:
  * Hàng chờ mạng (`Network Queue`) và luồng mã hóa (`Encoder Queue`) phải độc lập 100% với luồng USB.
  * Nếu mạng chậm, ứng dụng chỉ bỏ qua (drop) gói tin ở tầng Streaming Network Queue, **tuyệt đối không bao giờ làm ngưng trệ luồng C++ USB Isochronous Reader**.

---

## 10. Thermal Controller Với State Machine Thích Ứng (Mát Máy)

* **Chiến lược kiểm soát nhiệt độ theo State Machine**:
  * `NORMAL`: 1080p60 @ Bitrate cao.
  * `WARM`: Giữ 1080p60, tự động giảm Bitrate.
  * `HOT`: Giảm FPS (60 -> 30 FPS) giảm 50% tải GPU/CPU.
  * `SEVERE`: Hạ độ phân giải/FPS + hiện cảnh báo hạ nhiệt.
* **Dynamic MediaCodec Query**: Tự động truy vấn `MediaCodecList` để chọn `Profile` (`AVCProfileHigh` / `Main` / `Baseline`) và `ColorFormat` tương thích phần cứng thực tế của từng mẫu SoC điện thoại.

---

## 11. USB Watchdog State Machine & Quy Trình Tái Phục Hồi An Toàn

* **State Machine Phục Hồi**:
  ```text
  RUNNING ──(Timeout 1.5s)──> SUSPECT ──(Timeout tiếp)──> RECOVERING ──> RESTART UVC ──> RUNNING
  ```
* **Quy trình Tái Phục Hồi Chuẩn Kỹ Thuật**:
  1. Hủy các URB đang treo qua `USBDEVFS_DISCARDURB`.
  2. Đọc thu hồi toàn bộ URB qua `USBDEVFS_REAPURBNDELAY`.
  3. Dừng luồng C++ Native Stream an toàn.
  4. Cấu hình lại `USBDEVFS_SETINTERFACE`.
  5. Nộp lại Ring Buffer URB mới và tiếp tục stream.

---

## 12. Phác Đồ Kế Hoạch Thử Nghiệm 4 Tầng (Verification Plan)

* **TEST 1 — USB**: 100 lần cắm/rút liên tục -> 0 native crash, 0 deadlock, 0 FD leak.
* **TEST 2 — Video**: 1080p30 / 1080p60 chạy liên tục 30-60 phút -> FPS ổn định, drop rate thấp, 0 vỡ hình.
* **TEST 3 — Thermal**: Theo dõi CPU, GPU, SoC thermal status, nhiệt độ pin, FPS, Bitrate, Dropped frames.
* **TEST 4 — Network**: Chuyển đổi linh hoạt Wi-Fi <-> 5G -> Luồng USB Video chạy liên tục 100% không bị ngắt.
