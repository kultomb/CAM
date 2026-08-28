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

## 7. Tách Độc Lập Đường Xử Lý Preview & Streaming Pipeline (Zero-Copy & Mát Máy)

* **Nguyên lý Đỉnh Cao**: Tuyệt đối **KHÔNG** dùng pipeline tạo rác GC: `MJPEG -> Bitmap ARGB_8888 -> Canvas -> Bitmap -> MediaCodec`. Pipeline này ngốn 500MB/s RAM, gây khựng GC stutter và khiến điện thoại nóng 48°C chỉ sau 5 phút.
* **Sơ đồ Đường Xử Lý Tách Độc Lập**:
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
  * Preview hiển thị qua `ANativeWindow` hoặc SurfaceDirect Zero-Copy.
  * Livestream mã hóa qua `MediaCodec` H.264 Hardware Encoder (`COLOR_FormatSurface` / Direct Surface Feed).

---

## 8. Luồng USB Thread Cách Ly Hoàn Toàn Với Mạng (Network Queue Isolation)

* **Bài học**: Khi tín hiệu mạng 5G / WiFi bị suy giảm hoặc lag, bộ đẩy RTMP/SRT bị nghẽn. Nếu không cách ly, bộ nghẽn mạng sẽ kéo chậm luồng C++ USB Reader, làm mất gói URB Isochronous và gây sọc hình / rớt kết nối USB.
* **Quy tắc**:
  * Hàng chờ mạng (`Network Queue`) và luồng mã hóa (`Encoder Queue`) phải độc lập 100% với luồng USB.
  * Nếu mạng chậm, ứng dụng chỉ bỏ qua (drop) gói tin ở tầng Streaming Network Queue, **tuyệt đối không bao giờ làm ngưng trệ luồng C++ USB Isochronous Reader**.

---

## 9. Bộ Kiểm Soát Nhiệt Độ Thích Ứng (Thermal & Adaptive Bitrate Controller)

* **Chiến lược giữ điện thoại luôn mát (32°C - 36°C)**:
  * **Bình thường (< 38°C)**: Cấu hình 1080p 60FPS @ Bitrate 6.0 Mbps.
  * **Ấm máy (38°C - 42°C)**: Giữ 1080p 60FPS, điều chỉnh Bitrate xuống 4.5 Mbps.
  * **Nóng máy (42°C - 45°C)**: Chuyển 1080p 30FPS @ Bitrate 3.5 Mbps (giảm 50% tải GPU/CPU).
  * **Rất nóng (> 45°C)**: Chuyển 720p 30FPS @ Bitrate 2.5 Mbps và hiện Toast cảnh báo hạ nhiệt.
* **MediaCodec Bitrate Mode**: Sử dụng `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR` (Variable Bitrate) kết hợp `AVCProfileHigh` để tự động giảm dung lượng ở phân cảnh ít chuyển động.

---

## 10. USB Watchdog & Cơ Chế Tự Động Phục Hồi (Auto Reconnect Recovery)

* **Nguyên tắc An Toàn**: Không để bất kỳ lỗi USB phần cứng hay rút cáp đột ngột làm ứng dụng bị crash.
* **Cơ chế Watchdog**:
  * Giám sát nếu trong 1.0 giây không nhận được gói tin MJPEG hoặc FPS = 0:
    1. Tự động ngắt luồng URB cũ qua `USBDEVFS_DISCARDURB`.
    2. Tự động tái nạp Data Plane C++ trong 100ms mà **không ngắt luồng RTMP/SRT** phát trực tiếp.
