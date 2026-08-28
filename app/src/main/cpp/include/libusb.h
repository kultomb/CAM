#ifndef LIBUSB_H
#define LIBUSB_H

#include <stdint.h>
#include <sys/types.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LIBUSB_CALL

typedef struct libusb_context libusb_context;
typedef struct libusb_device_handle libusb_device_handle;

enum libusb_error {
    LIBUSB_SUCCESS = 0,
    LIBUSB_ERROR_IO = -1,
    LIBUSB_ERROR_INVALID_PARAM = -2,
    LIBUSB_ERROR_ACCESS = -3,
    LIBUSB_ERROR_NO_DEVICE = -4,
    LIBUSB_ERROR_NOT_FOUND = -5,
    LIBUSB_ERROR_BUSY = -6,
    LIBUSB_ERROR_TIMEOUT = -7,
    LIBUSB_ERROR_OVERFLOW = -8,
    LIBUSB_ERROR_PIPE = -9,
    LIBUSB_ERROR_INTERRUPTED = -10,
    LIBUSB_ERROR_NO_MEM = -11,
    LIBUSB_ERROR_NOT_SUPPORTED = -12,
    LIBUSB_ERROR_OTHER = -99,
};

enum libusb_transfer_status {
    LIBUSB_TRANSFER_COMPLETED,
    LIBUSB_TRANSFER_ERROR,
    LIBUSB_TRANSFER_TIMED_OUT,
    LIBUSB_TRANSFER_CANCELLED,
    LIBUSB_TRANSFER_STALL,
    LIBUSB_TRANSFER_NO_DEVICE,
    LIBUSB_TRANSFER_OVERFLOW,
};

enum libusb_option {
    LIBUSB_OPTION_LOG_LEVEL = 0,
    LIBUSB_OPTION_USE_USBDK = 1,
    LIBUSB_OPTION_NO_DEVICE_DISCOVERY = 2,
};

struct libusb_iso_packet_descriptor {
    unsigned int length;
    unsigned int actual_length;
    enum libusb_transfer_status status;
};

struct libusb_transfer;

typedef void (LIBUSB_CALL *libusb_transfer_cb_fn)(struct libusb_transfer *transfer);

struct libusb_transfer {
    libusb_device_handle *dev_handle;
    uint8_t flags;
    unsigned char endpoint;
    unsigned char type;
    unsigned int timeout;
    enum libusb_transfer_status status;
    int length;
    int actual_length;
    libusb_transfer_cb_fn callback;
    void *user_data;
    unsigned char *buffer;
    int num_iso_packets;
    struct libusb_iso_packet_descriptor iso_packet_desc[];
};

int LIBUSB_CALL libusb_init(libusb_context **ctx);
void LIBUSB_CALL libusb_exit(libusb_context *ctx);
int LIBUSB_CALL libusb_set_option(libusb_context *ctx, enum libusb_option option, ...);
int LIBUSB_CALL libusb_wrap_sys_device(libusb_context *ctx, intptr_t sys_dev, libusb_device_handle **dev_handle);
void LIBUSB_CALL libusb_close(libusb_device_handle *dev_handle);

struct libusb_transfer * LIBUSB_CALL libusb_alloc_transfer(int iso_packets);
void LIBUSB_CALL libusb_free_transfer(struct libusb_transfer *transfer);
int LIBUSB_CALL libusb_submit_transfer(struct libusb_transfer *transfer);
int LIBUSB_CALL libusb_cancel_transfer(struct libusb_transfer *transfer);

int LIBUSB_CALL libusb_handle_events_timeout(libusb_context *ctx, struct timeval *tv);
const char * LIBUSB_CALL libusb_error_name(int errcode);

static inline void libusb_fill_iso_transfer(struct libusb_transfer *transfer,
    libusb_device_handle *dev_handle, unsigned char endpoint,
    unsigned char *buffer, int length, int num_iso_packets,
    libusb_transfer_cb_fn callback, void *user_data, unsigned int timeout)
{
    transfer->dev_handle = dev_handle;
    transfer->endpoint = endpoint;
    transfer->type = 1; // ISOCHRONOUS
    transfer->timeout = timeout;
    transfer->buffer = buffer;
    transfer->length = length;
    transfer->num_iso_packets = num_iso_packets;
    transfer->callback = callback;
    transfer->user_data = user_data;
}

static inline void libusb_set_iso_packet_lengths(struct libusb_transfer *transfer,
    unsigned int length)
{
    for (int i = 0; i < transfer->num_iso_packets; i++)
        transfer->iso_packet_desc[i].length = length;
}

static inline unsigned char *libusb_get_iso_packet_buffer_simple(
    struct libusb_transfer *transfer, unsigned int packet)
{
    return transfer->buffer + (packet * transfer->iso_packet_desc[0].length);
}

#ifdef __cplusplus
}
#endif

#endif // LIBUSB_H
