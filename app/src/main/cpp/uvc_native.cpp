#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <errno.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define TAG "HBG-UVC-NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int ISO_PACKETS = 32;
static constexpr int URB_COUNT = 8;
static constexpr size_t MAX_FRAME_SIZE = 4 * 1024 * 1024;

struct UvcEngineContext {
    int fd = -1;
    int ifaceId = 1;
    int epAddr = 0x83;
    int maxPacketSize = 1024;
    int altSetting = 1;

    std::atomic<bool> running{false};
    std::thread workerThread;

    JavaVM* jvm = nullptr;
    jobject bridgeObject = nullptr;
    jmethodID onFrameMethod = nullptr;

    std::mutex frameMutex;
    std::vector<uint8_t> frameBuffer;
    uint8_t lastFid = 0xFF;

    uint64_t frameCount = 0;
    std::chrono::steady_clock::time_point fpsStart;
    int fpsFrames = 0;
};

static UvcEngineContext* g_ctx = nullptr;
static std::mutex g_ctxMutex;

static void deliverFrame(UvcEngineContext* ctx, const uint8_t* data, size_t length) {
    if (!ctx || !ctx->running || !data || length < 100) return;

    JNIEnv* env = nullptr;
    bool attached = false;

    if (ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    if (ctx->bridgeObject && ctx->onFrameMethod) {
        jbyteArray jpeg = env->NewByteArray(static_cast<jsize>(length));
        if (jpeg) {
            env->SetByteArrayRegion(jpeg, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
            env->CallVoidMethod(ctx->bridgeObject, ctx->onFrameMethod, jpeg);
            env->DeleteLocalRef(jpeg);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    if (attached) {
        ctx->jvm->DetachCurrentThread();
    }
}

static void workerLoop(UvcEngineContext* ctx) {
    int packetSize = (ctx->maxPacketSize > 0) ? ctx->maxPacketSize : 1024;
    int targetEp = (ctx->epAddr != 0) ? ctx->epAddr : 0x83;
    int altSetting = (ctx->altSetting > 0) ? ctx->altSetting : 1;
    int ifaceId = ctx->ifaceId;

    LOGI("🟢 UVC Direct Render Engine START (fd=%d, iface=%d, ep=0x%02X, packetSize=%d, alt=%d)", 
         ctx->fd, ifaceId, targetEp, packetSize, altSetting);

    int claimRc = ioctl(ctx->fd, USBDEVFS_CLAIMINTERFACE, &ifaceId);
    if (claimRc < 0) {
        LOGE("❌ USBDEVFS_CLAIMINTERFACE (iface=%d) errno=%d (%s)", ifaceId, errno, strerror(errno));
    }

    struct usbdevfs_setinterface setif;
    setif.interface = ifaceId;
    setif.altsetting = altSetting;
    int setIfRc = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &setif);
    if (setIfRc < 0) {
        LOGE("❌ USBDEVFS_SETINTERFACE (iface=%d, alt=%d) errno=%d (%s)", ifaceId, altSetting, errno, strerror(errno));
    }

    // Tự động thử nghiệm kích thước packetSize (1024 -> 512 -> 192 -> 128) nếu Kernel báo Message too long
    int sizesToTry[] = { packetSize, 1024, 960, 896, 768, 512, 384, 192, 128 };
    int workingPacketSize = packetSize;

    for (int sizeCandidate : sizesToTry) {
        if (sizeCandidate <= 0) continue;
        int testUrbSize = ISO_PACKETS * sizeCandidate;
        size_t urbStructSize = sizeof(struct usbdevfs_urb) + (ISO_PACKETS * sizeof(struct usbdevfs_iso_packet_desc));
        std::vector<uint8_t> testUrbMem(urbStructSize, 0);
        std::vector<uint8_t> testDataBuf(testUrbSize, 0);

        struct usbdevfs_urb* testUrb = reinterpret_cast<struct usbdevfs_urb*>(testUrbMem.data());
        testUrb->type = USBDEVFS_URB_TYPE_ISO;
        testUrb->endpoint = targetEp;
        testUrb->flags = USBDEVFS_URB_ISO_ASAP;
        testUrb->buffer = testDataBuf.data();
        testUrb->buffer_length = testUrbSize;
        testUrb->number_of_packets = ISO_PACKETS;
        for (int p = 0; p < ISO_PACKETS; ++p) {
            testUrb->iso_frame_desc[p].length = sizeCandidate;
        }

        int rc = ioctl(ctx->fd, USBDEVFS_SUBMITURB, testUrb);
        if (rc == 0) {
            workingPacketSize = sizeCandidate;
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, testUrb);
            LOGI("🟢 Đã tìm thấy kích thước Kernel PacketSize chấp thuận: %d bytes", workingPacketSize);
            break;
        } else if (errno != EMSGSIZE) {
            // Lỗi khác EMSGSIZE nghĩa là URB đã được chấp nhận bởi host controller
            workingPacketSize = sizeCandidate;
            LOGI("🟢 Chấp thuận Kernel PacketSize: %d bytes (rc=%d, errno=%d)", workingPacketSize, rc, errno);
            break;
        }
    }

    packetSize = workingPacketSize;
    int urbBufferSize = ISO_PACKETS * packetSize;
    size_t urbStructSize = sizeof(struct usbdevfs_urb) + (ISO_PACKETS * sizeof(struct usbdevfs_iso_packet_desc));
    std::vector<std::vector<uint8_t>> urbMemories(URB_COUNT, std::vector<uint8_t>(urbStructSize, 0));
    std::vector<std::vector<uint8_t>> dataBuffers(URB_COUNT, std::vector<uint8_t>(urbBufferSize, 0));

    for (int i = 0; i < URB_COUNT; ++i) {
        struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(urbMemories[i].data());
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = targetEp;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = dataBuffers[i].data();
        urb->buffer_length = urbBufferSize;
        urb->number_of_packets = ISO_PACKETS;

        for (int p = 0; p < ISO_PACKETS; ++p) {
            urb->iso_frame_desc[p].length = packetSize;
        }

        int submitRc = ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb);
        if (submitRc < 0) {
            LOGE("❌ SUBMITURB URB[%d] (size=%d) errno=%d (%s)", i, packetSize, errno, strerror(errno));
        }
    }

    ctx->fpsStart = std::chrono::steady_clock::now();
    uint64_t totalBytesReceived = 0;
    int emptyReapCount = 0;

    while (ctx->running) {
        struct usbdevfs_urb* reapedUrb = nullptr;
        int rc = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reapedUrb);
        if (rc == 0 && reapedUrb != nullptr) {
            emptyReapCount = 0;
            uint8_t* buffer = reinterpret_cast<uint8_t*>(reapedUrb->buffer);
            for (int p = 0; p < reapedUrb->number_of_packets; ++p) {
                const auto& desc = reapedUrb->iso_frame_desc[p];
                if (desc.actual_length > 2) {
                    int offset = p * packetSize;
                    uint8_t headerLen = buffer[offset];
                    uint8_t flags = buffer[offset + 1];

                    if (headerLen >= 2 && headerLen <= desc.actual_length) {
                        uint8_t fid = flags & 1;
                        const uint8_t* payload = buffer + offset + headerLen;
                        size_t payloadLen = desc.actual_length - headerLen;

                        if (payloadLen > 0) {
                            totalBytesReceived += payloadLen;

                            // Nhận diện JPEG SOI (0xFF, 0xD8) - Khởi đầu khung hình mới
                            if (payloadLen >= 2 && payload[0] == 0xFF && payload[1] == 0xD8) {
                                if (!ctx->frameBuffer.empty() && ctx->frameBuffer.size() > 1000) {
                                    deliverFrame(ctx, ctx->frameBuffer.data(), ctx->frameBuffer.size());
                                    ctx->frameCount++;
                                    ctx->fpsFrames++;
                                }
                                ctx->frameBuffer.clear();
                            }

                            if (ctx->frameBuffer.size() + payloadLen <= MAX_FRAME_SIZE) {
                                ctx->frameBuffer.insert(ctx->frameBuffer.end(), payload, payload + payloadLen);
                            }

                            // Nhận diện JPEG EOI (0xFF, 0xD9) - Kết thúc khung hình
                            size_t bufSize = ctx->frameBuffer.size();
                            if (bufSize >= 2 && ctx->frameBuffer[bufSize - 2] == 0xFF && ctx->frameBuffer[bufSize - 1] == 0xD9) {
                                deliverFrame(ctx, ctx->frameBuffer.data(), bufSize);
                                ctx->frameCount++;
                                ctx->fpsFrames++;
                                ctx->frameBuffer.clear();
                            }
                        }

                        if (fid != ctx->lastFid && ctx->lastFid != 0xFF) {
                            if (!ctx->frameBuffer.empty() && ctx->frameBuffer.size() > 1000) {
                                deliverFrame(ctx, ctx->frameBuffer.data(), ctx->frameBuffer.size());
                                ctx->frameCount++;
                                ctx->fpsFrames++;
                                ctx->frameBuffer.clear();
                            }
                        }
                        ctx->lastFid = fid;
                    }
                }
            }

            auto now = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - ctx->fpsStart).count();
            if (elapsed >= 1000) {
                float fps = (ctx->fpsFrames * 1000.0f) / elapsed;
                LOGI("📊 UVC Native FPS: %.1f (Tổng khung hình: %llu, Đã nhận: %llu KB)", 
                     fps, (unsigned long long)ctx->frameCount, (unsigned long long)(totalBytesReceived / 1024));
                ctx->fpsStart = now;
                ctx->fpsFrames = 0;
            }

            for (int p = 0; p < ISO_PACKETS; ++p) {
                reapedUrb->iso_frame_desc[p].length = packetSize;
                reapedUrb->iso_frame_desc[p].actual_length = 0;
                reapedUrb->iso_frame_desc[p].status = 0;
            }
            ioctl(ctx->fd, USBDEVFS_SUBMITURB, reapedUrb);
        } else {
            emptyReapCount++;
            if (emptyReapCount % 2000 == 0) {
                LOGI("⏳ Đang đợi dữ liệu ISOC từ USB Capture (Empty reap count: %d, errno=%d)...", emptyReapCount, errno);
            }
            std::this_thread::sleep_for(std::chrono::microseconds(500));
        }
    }

    for (int i = 0; i < URB_COUNT; ++i) {
        struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(urbMemories[i].data());
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, urb);
    }
    LOGI("⏹ UVC Direct Render Engine Dừng Thành Công.");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeStartEngine(
    JNIEnv* env, jobject thiz, jint fd, jint ifaceId, jint epAddr, jint maxPacketSize, jint altSetting, jobject surface) {

    std::lock_guard<std::mutex> lock(g_ctxMutex);
    if (g_ctx != nullptr) {
        LOGE("Engine native đang chạy, dừng phiên trước...");
        g_ctx->running = false;
        if (g_ctx->workerThread.joinable()) {
            g_ctx->workerThread.join();
        }
        delete g_ctx;
        g_ctx = nullptr;
    }

    auto ctx = new UvcEngineContext();
    ctx->fd = fd;
    ctx->ifaceId = ifaceId;
    ctx->epAddr = epAddr;
    ctx->maxPacketSize = maxPacketSize;
    ctx->altSetting = altSetting;
    env->GetJavaVM(&ctx->jvm);
    ctx->bridgeObject = env->NewGlobalRef(thiz);

    jclass cls = env->GetObjectClass(thiz);
    ctx->onFrameMethod = env->GetMethodID(cls, "onNativeFrame", "([B)V");

    ctx->running = true;
    ctx->workerThread = std::thread(workerLoop, ctx);

    g_ctx = ctx;
    LOGI("🟢 Native StartEngine thành công (fd=%d, iface=%d, ep=0x%02X, packetSize=%d, alt=%d)", 
         fd, ifaceId, epAddr, maxPacketSize, altSetting);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeStopEngine(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    if (g_ctx != nullptr) {
        g_ctx->running = false;
        if (g_ctx->workerThread.joinable()) {
            g_ctx->workerThread.join();
        }
        if (g_ctx->bridgeObject) {
            env->DeleteGlobalRef(g_ctx->bridgeObject);
        }
        delete g_ctx;
        g_ctx = nullptr;
        LOGI("⏹ Native StopEngine hoàn tất.");
    }
}
