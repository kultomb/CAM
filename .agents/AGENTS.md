# AGENTS.md - Quy Tắc Vàng Kỹ Thuật Nhận Camera USB (UVC) Trên Android 14/15 (16KB Page Alignment)

> [!IMPORTANT]
> **TÀI LIỆU QUY TẮC CỐT LÕI CỦA DỰ ÁN (MANDATORY PROJECT RULES)**
> File này chứa toàn bộ kiến thức thực nghiệm thu được sau khi giải quyết thành công bài toán nhận dạng USB Capture Card (Sony A73 / MS2109) trên Android 14/15. Khi chỉnh sửa mã nguồn sau này, BẤT KỲ NGUYÊN TẮC NÀO TRONG ĐÂY CŨNG KHÔNG ĐƯỢC PHÉP VI PHẠM ĐỂ TRÁNH LÀM HỎNG TÍNH NĂNG NHẬN CAMERA.

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

## 7. Cơ Chế Bỏ Qua Khung Hình Ứ Động (Zero-Lag Frame Dropping) & Full Color 32-bit

* **Vấn đề**: Đẩy 30–60 FPS liên tục lên Canvas nếu không xử lý kịp sẽ làm dâng tràn hàng chờ của Android WindowManager, khiến màn hình bị giật lag và tự động đen màn sau vài giây.
* **Quy tắc**:
  * Sử dụng `AtomicBoolean` frame dropping (`isRendering.compareAndSet(false, true)`) trong [`UvcOfficialEngine.kt`](file:///c:/Users/CMD/Desktop/LIVE%20CAMERA/app/src/main/java/com/hbg/live/capture/UvcOfficialEngine.kt). Nếu UI đang bận vẽ, bỏ qua khung hình mới trong **0.0001ms**.
  * Sử dụng giải mã `Bitmap.Config.ARGB_8888` để giữ 100% màu sắc và độ nét thực tế của cảm biến Sony A73.
  * Đẩy livestream qua MediaCodec H.264 Encoder `AVCProfileHigh` tại Bitrate 4.5-6.0 Mbps, GOP 1s chuẩn YouTube Live / Facebook Live.
