#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <cstring>
#include <cerrno>
#include <vector>
#include <thread>
#include <atomic>
#include <chrono>
#include <algorithm>

#define LOG_TAG "HBG-UVC-NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static const int ISO_PACKETS = 32;
static const int URB_COUNT = 16;
static const size_t MAX_FRAME_SIZE = 4 * 1024 * 1024; // 4MB đệm an toàn cho MJPEG 4K

// Bảng Huffman tiêu chuẩn UVC (420 bytes) cho luồng MJPEG từ MS2109 / Sony A7
static const uint8_t DEFAULT_HUFFMAN_TABLE[] = {
    0xFF, 0xC4, 0x01, 0xA2,
    0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
    0x01, 0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
    0x10, 0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x01, 0x7D,
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88,
    0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2,
    0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5,
    0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8,
    0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3,
    0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
    0x11, 0x00, 0x02, 0x01, 0x02, 0x04, 0x04, 0x03, 0x04, 0x07, 0x05, 0x04, 0x04, 0x00, 0x01, 0x7D,
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88,
    0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2,
    0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5,
    0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8,
    0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3,
    0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA
};

struct UvcEngineContext {
    int fd = -1;
    int ifaceId = 1;
    int epAddr = 0x83;
    int maxPacketSize = 1024;
    int altSetting = 1;
    std::atomic<bool> running{false};
    std::thread workerThread;

    JavaVM* jvm = nullptr;
    jobject listenerRef = nullptr;
    jmethodID onNativeFrameMethod = nullptr;
    jmethodID onNativeErrorMethod = nullptr;

    std::vector<uint8_t> frameBuffer;
    uint8_t lastFid = 0xFF;
    uint64_t frameCount = 0;
    uint64_t fpsFrames = 0;
    std::chrono::steady_clock::time_point fpsStart;
};

static void deliverFrame(UvcEngineContext* ctx, const uint8_t* data, size_t size) {
    if (ctx == nullptr || ctx->jvm == nullptr || ctx->listenerRef == nullptr || ctx->onNativeFrameMethod == nullptr) return;

    JNIEnv* env = nullptr;
    jint getEnvStat = ctx->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    bool attached = false;

    if (getEnvStat == JNI_EDETACHED) {
        if (ctx->jvm->AttachCurrentThread(&env, nullptr) != 0) {
            return;
        }
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jbyteArray byteArray = env->NewByteArray(static_cast<jsize>(size));
    if (byteArray != nullptr) {
        env->SetByteArrayRegion(byteArray, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(ctx->listenerRef, ctx->onNativeFrameMethod, byteArray);
        env->DeleteLocalRef(byteArray);
    }

    if (attached) {
        ctx->jvm->DetachCurrentThread();
    }
}

static void extractAndDeliverJpeg(UvcEngineContext* ctx) {
    size_t sz = ctx->frameBuffer.size();
    if (sz < 100) return;

    // 1. Tìm mốc SOI (0xFF, 0xD8) chuẩn xác ở đầu khung ảnh
    size_t soiPos = 0;
    bool foundSoi = false;
    for (size_t i = 0; i + 1 < sz; ++i) {
        if (ctx->frameBuffer[i] == 0xFF && ctx->frameBuffer[i + 1] == 0xD8) {
            soiPos = i;
            foundSoi = true;
            break;
        }
    }
    if (!foundSoi) return;

    // 2. Tìm mốc EOI (0xFF, 0xD9) chuẩn xác ở cuối khung ảnh
    size_t eoiPos = 0;
    bool foundEoi = false;
    for (size_t i = sz; i >= soiPos + 2; --i) {
        if (ctx->frameBuffer[i - 2] == 0xFF && ctx->frameBuffer[i - 1] == 0xD9) {
            eoiPos = i;
            foundEoi = true;
            break;
        }
    }
    if (!foundEoi) return;

    size_t jpegLen = eoiPos - soiPos;
    if (jpegLen >= 100) {
        // Tự động kiểm tra và tiêm bảng Huffman tiêu chuẩn UVC trực tiếp trong C++ Native (Zero GC allocation)
        bool hasDht = false;
        size_t checkLen = std::min<size_t>(jpegLen, 512);
        for (size_t i = soiPos; i + 1 < soiPos + checkLen; ++i) {
            if (ctx->frameBuffer[i] == 0xFF && ctx->frameBuffer[i + 1] == 0xC4) {
                hasDht = true;
                break;
            }
        }

        if (!hasDht) {
            std::vector<uint8_t> outJpeg;
            outJpeg.reserve(jpegLen + sizeof(DEFAULT_HUFFMAN_TABLE));
            outJpeg.push_back(0xFF);
            outJpeg.push_back(0xD8);
            outJpeg.insert(outJpeg.end(), DEFAULT_HUFFMAN_TABLE, DEFAULT_HUFFMAN_TABLE + sizeof(DEFAULT_HUFFMAN_TABLE));
            outJpeg.insert(outJpeg.end(), ctx->frameBuffer.begin() + soiPos + 2, ctx->frameBuffer.begin() + eoiPos);
            deliverFrame(ctx, outJpeg.data(), outJpeg.size());
        } else {
            deliverFrame(ctx, ctx->frameBuffer.data() + soiPos, jpegLen);
        }

        ctx->frameCount++;
        ctx->fpsFrames++;
    }
}

static void workerLoop(UvcEngineContext* ctx) {
    int packetStride = (ctx->maxPacketSize > 0) ? ctx->maxPacketSize : 1024;
    int packetSize = packetStride;
    int targetEp = (ctx->epAddr != 0) ? ctx->epAddr : 0x83;
    int altSetting = (ctx->altSetting > 0) ? ctx->altSetting : 1;
    int ifaceId = ctx->ifaceId;

    LOGI("🟢 UVC Precision Render Engine START (fd=%d, iface=%d, ep=0x%02X, packetSize=%d, alt=%d)", 
         ctx->fd, ifaceId, targetEp, packetSize, altSetting);

    int claimRc = ioctl(ctx->fd, USBDEVFS_CLAIMINTERFACE, &ifaceId);
    if (claimRc < 0) {
        LOGE("❌ USBDEVFS_CLAIMINTERFACE (iface=%d) errno=%d (%s)", ifaceId, errno, strerror(errno));
    }

    struct usbdevfs_setinterface setif;
    setif.interface = ifaceId;
    setif.altsetting = altSetting;
    int setRc = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &setif);
    if (setRc < 0) {
        LOGE("❌ USBDEVFS_SETINTERFACE (alt=%d) errno=%d (%s)", altSetting, errno, strerror(errno));
    }

    int urbBufferSize = ISO_PACKETS * packetStride;
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
            urb->iso_frame_desc[p].length = packetStride;
        }

        int submitRc = ioctl(ctx->fd, USBDEVFS_SUBMITURB, urb);
        if (submitRc < 0) {
            LOGE("❌ URB [%d] SUBMIT FAILED! errno=%d (%s)", i, errno, strerror(errno));
        }
    }

    ctx->lastFid = 0xFF;
    ctx->frameCount = 0;
    ctx->fpsFrames = 0;
    ctx->fpsStart = std::chrono::steady_clock::now();
    uint64_t totalBytesReceived = 0;

    while (ctx->running) {
        struct usbdevfs_urb* reapedUrb = nullptr;
        int rc = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reapedUrb);
        if (rc == 0 && reapedUrb != nullptr) {
            uint8_t* buffer = reinterpret_cast<uint8_t*>(reapedUrb->buffer);
            for (int p = 0; p < reapedUrb->number_of_packets; ++p) {
                const auto& desc = reapedUrb->iso_frame_desc[p];
                if (desc.actual_length > 2) {
                    int offset = p * packetStride;
                    uint8_t headerLen = buffer[offset];
                    uint8_t headerFlags = buffer[offset + 1];

                    if (headerLen >= 2 && headerLen <= 12 && headerLen <= desc.actual_length && (headerFlags & 0x40) == 0) {
                        const uint8_t* payload = buffer + offset + headerLen;
                        size_t payloadLen = desc.actual_length - headerLen;

                        if (payloadLen > 0) {
                            totalBytesReceived += payloadLen;

                            uint8_t fid = headerFlags & 0x01;
                            bool fidToggled = (ctx->lastFid != 0xFF && fid != ctx->lastFid);
                            bool isEof = (headerFlags & 0x02) != 0;

                            if (fidToggled) {
                                if (!ctx->frameBuffer.empty()) {
                                    extractAndDeliverJpeg(ctx);
                                    ctx->frameBuffer.clear();
                                }
                                ctx->lastFid = fid;
                            }

                            if (ctx->frameBuffer.size() + payloadLen <= MAX_FRAME_SIZE) {
                                ctx->frameBuffer.insert(ctx->frameBuffer.end(), payload, payload + payloadLen);
                            }

                            if (isEof) {
                                extractAndDeliverJpeg(ctx);
                                ctx->frameBuffer.clear();
                            }
                        }
                    }
                }
            }

            // Nộp lại URB tiếp tục vòng lặp Ring Buffer mà không bỏ lỡ gói
            ioctl(ctx->fd, USBDEVFS_SUBMITURB, reapedUrb);
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }

        auto now = std::chrono::steady_clock::now();
        auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - ctx->fpsStart).count();
        if (elapsedMs >= 1000) {
            float fps = (ctx->fpsFrames * 1000.0f) / elapsedMs;
            LOGI("📊 UVC Native FPS: %.1f (Tổng khung hình: %llu, Đã nhận: %llu KB)", fps, (unsigned long long)ctx->frameCount, (unsigned long long)(totalBytesReceived / 1024));
            ctx->fpsFrames = 0;
            ctx->fpsStart = now;
        }
    }

    for (int i = 0; i < URB_COUNT; ++i) {
        struct usbdevfs_urb* urb = reinterpret_cast<struct usbdevfs_urb*>(urbMemories[i].data());
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, urb);
    }

    struct usbdevfs_urb* reapedUrb = nullptr;
    while (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &reapedUrb) == 0) {}

    ioctl(ctx->fd, USBDEVFS_RELEASEINTERFACE, &ifaceId);
    LOGI("⏹ UVC Direct Render Engine Dừng Thành Công.");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeStart(
    JNIEnv* env,
    jobject instance,
    jint fd,
    jint ifaceId,
    jint epAddr,
    jint maxPacketSize,
    jint altSetting,
    jobject surface) {

    auto* ctx = new UvcEngineContext();
    ctx->fd = fd;
    ctx->ifaceId = ifaceId;
    ctx->epAddr = epAddr;
    ctx->maxPacketSize = maxPacketSize;
    ctx->altSetting = altSetting;

    env->GetJavaVM(&(ctx->jvm));
    ctx->listenerRef = env->NewGlobalRef(instance);

    jclass cls = env->GetObjectClass(instance);
    ctx->onNativeFrameMethod = env->GetMethodID(cls, "onNativeFrame", "([B)V");
    ctx->onNativeErrorMethod = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");

    ctx->running = true;
    ctx->workerThread = std::thread(workerLoop, ctx);

    LOGI("🟢 Native StartEngine thành công (fd=%d, iface=%d, ep=0x%02X, packetSize=%d, alt=%d)", 
         fd, ifaceId, epAddr, maxPacketSize, altSetting);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hbg_live_capture_UvcNativeBridge_nativeStop(
    JNIEnv* env,
    jobject instance,
    jlong contextPtr) {

    if (contextPtr == 0) return;
    auto* ctx = reinterpret_cast<UvcEngineContext*>(contextPtr);

    ctx->running = false;
    if (ctx->workerThread.joinable()) {
        ctx->workerThread.join();
    }

    if (ctx->listenerRef != nullptr) {
        env->DeleteGlobalRef(ctx->listenerRef);
        ctx->listenerRef = nullptr;
    }

    delete ctx;
    LOGI("⏹ Native StopEngine hoàn tất.");
}
