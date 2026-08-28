#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

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
    int epAddr = 0x83;
    int maxPacketSize = 3072;
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
    if (!ctx || !ctx->running || !data || length == 0) return;

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
    int packetSize = (ctx->maxPacketSize > 0) ? ctx->maxPacketSize : 3072;
    int targetEp = (ctx->epAddr != 0) ? ctx->epAddr : 0x83;
    int altSetting = (ctx->altSetting > 0) ? ctx->altSetting : 1;

    int urbBufferSize = ISO_PACKETS * packetSize;

    LOGI("🟢 16KB UVC Direct Render Engine START (fd=%d, ep=0x%02X, packetSize=%d, alt=%d)", 
         ctx->fd, targetEp, packetSize, altSetting);

    int ifnum = 1;
    ioctl(ctx->fd, USBDEVFS_CLAIMINTERFACE, &ifnum);

    struct usbdevfs_setinterface setif;
    setif.interface = 1;
    setif.altsetting = altSetting;
    ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &setif);

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

        ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb);
    }

    ctx->fpsStart = std::chrono::steady_clock::now();

    while (ctx->running) {
        struct usbdevfs_urb* reapedUrb = nullptr;
        int rc = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reapedUrb);
        if (rc == 0 && reapedUrb != nullptr) {
            uint8_t* buffer = reinterpret_cast<uint8_t*>(reapedUrb->buffer);
            for (int p = 0; p < reapedUrb->number_of_packets; ++p) {
                const auto& desc = reapedUrb->iso_frame_desc[p];
                if (desc.status == 0 && desc.actual_length > 2) {
                    int offset = p * packetSize;
                    uint8_t headerLen = buffer[offset];
                    uint8_t flags = buffer[offset + 1];

                    if (headerLen >= 2 && headerLen <= desc.actual_length) {
                        uint8_t fid = flags & 1;
                        const uint8_t* payload = buffer + offset + headerLen;
                        size_t payloadLen = desc.actual_length - headerLen;

                        if (fid != ctx->lastFid && ctx->lastFid != 0xFF) {
                            if (!ctx->frameBuffer.empty()) {
                                deliverFrame(ctx, ctx->frameBuffer.data(), ctx->frameBuffer.size());
                                ctx->frameCount++;
                                ctx->fpsFrames++;

                                auto now = std::chrono::steady_clock::now();
                                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - ctx->fpsStart).count();
                                if (elapsed >= 1000) {
                                    float fps = (ctx->fpsFrames * 1000.0f) / elapsed;
                                    LOGI("📊 UVC Native FPS: %.1f (Tổng khung hình: %llu)", fps, (unsigned long long)ctx->frameCount);
                                    ctx->fpsStart = now;
                                    ctx->fpsFrames = 0;
                                }
                            }
                            ctx->frameBuffer.clear();
                        }
                        ctx->lastFid = fid;

                        if (payloadLen > 0 && ctx->frameBuffer.size() + payloadLen <= MAX_FRAME_SIZE) {
                            ctx->frameBuffer.insert(ctx->frameBuffer.end(), payload, payload + payloadLen);
                        }
                    }
                }
            }

            for (int p = 0; p < ISO_PACKETS; ++p) {
                reapedUrb->iso_frame_desc[p].length = packetSize;
                reapedUrb->iso_frame_desc[p].actual_length = 0;
                reapedUrb->iso_frame_desc[p].status = 0;
            }
            ioctl(ctx->fd, USBDEVFS_SUBMITURB, reapedUrb);
        } else {
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
    JNIEnv* env, jobject thiz, jint fd, jint epAddr, jint maxPacketSize, jint altSetting, jobject surface) {

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
    LOGI("🟢 Native StartEngine thành công (fd=%d, ep=0x%02X, packetSize=%d, alt=%d)", 
         fd, epAddr, maxPacketSize, altSetting);
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
