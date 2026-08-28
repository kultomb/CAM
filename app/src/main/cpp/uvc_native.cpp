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
    int altSetting = 3;

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
    int packetSize = 3072;
    int urbBufferSize = ISO_PACKETS * packetSize;
    int targetEp = 0x83;

    LOGI("🟢 16KB UVC Direct Render Engine START (fd=%d, ep=0x%02X)", ctx->fd, targetEp);

    int ifnum = 1;
    ioctl(ctx->fd, USBDEVFS_CLAIMINTERFACE, &ifnum);

    struct usbdevfs_setinterface setif;
    setif.interface = 1;
    setif.altsetting = ctx->altSetting;
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
                        bool fid = (flags & 0x01) != 0;
                        bool err = (flags & 0x40) != 0;

                        if (!err) {
                            uint8_t currentFid = fid ? 1 : 0;
                            if (ctx->lastFid != 0xFF && ctx->lastFid != currentFid) {
                                std::vector<uint8_t> frame;
                                {
                                    std::lock_guard<std::mutex> lock(ctx->frameMutex);
                                    frame.swap(ctx->frameBuffer);
                                }
                                if (frame.size() > 500) {
                                    ctx->fpsFrames++;
                                    deliverFrame(ctx, frame.data(), frame.size());
                                }
                            }
                            ctx->lastFid = currentFid;

                            int payloadLen = desc.actual_length - headerLen;
                            if (payloadLen > 0) {
                                std::lock_guard<std::mutex> lock(ctx->frameMutex);
                                if (ctx->frameBuffer.size() + payloadLen < MAX_FRAME_SIZE) {
                                    ctx->frameBuffer.insert(ctx->frameBuffer.end(), buffer + offset + headerLen, buffer + offset + desc.actual_length);
                                } else {
                                    ctx->frameBuffer.clear();
                                }
                            }
                        }
                    }
                }
            }

            // Resubmit URB
            if (ctx->running) {
                ioctl(ctx->fd, USBDEVFS_SUBMITURB, reapedUrb);
            }
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }

        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - ctx->fpsStart).count();
        if (elapsed >= 1000) {
            LOGI("🟢 [16KB UVC REALTIME ISO ENGINE] FPS=%d", ctx->fpsFrames);
            ctx->fpsFrames = 0;
            ctx->fpsStart = now;
        }
    }

    ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ifnum);
    LOGI("USBDEVFS_RELEASEINTERFACE Interface 1 released cleanly");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeStart(JNIEnv* env, jobject thiz, jint fd, jint epAddr, jint maxPacketSize, jint altSetting, jobject) {
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    if (g_ctx) return -1;

    UvcEngineContext* ctx = new UvcEngineContext();
    ctx->fd = fd;
    ctx->epAddr = 0x83;
    ctx->maxPacketSize = 3072;
    ctx->altSetting = altSetting;

    env->GetJavaVM(&ctx->jvm);
    ctx->bridgeObject = env->NewGlobalRef(thiz);

    jclass clazz = env->GetObjectClass(thiz);
    ctx->onFrameMethod = env->GetMethodID(clazz, "onNativeFrame", "([B)V");
    env->DeleteLocalRef(clazz);

    ctx->running = true;
    ctx->workerThread = std::thread(workerLoop, ctx);

    g_ctx = ctx;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeStop(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    if (!g_ctx) return;
    UvcEngineContext* ctx = g_ctx;
    g_ctx = nullptr;

    ctx->running = false;
    if (ctx->workerThread.joinable()) {
        ctx->workerThread.join();
    }
    if (ctx->bridgeObject) {
        env->DeleteGlobalRef(ctx->bridgeObject);
    }
    delete ctx;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeIsRunning(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_ctxMutex);
    return g_ctx && g_ctx->running;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    LOGI("HBG UVC Direct ISO Render Native loaded");
    return JNI_VERSION_1_6;
}
