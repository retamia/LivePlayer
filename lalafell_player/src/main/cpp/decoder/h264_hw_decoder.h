//
// Created by retamia on 2018/10/24.
//

#ifndef LIVEPLAYER_H264_HW_DECODER_H
#define LIVEPLAYER_H264_HW_DECODER_H

#include <atomic>

#include <media/NdkMediaCodec.h>

#include "util/thread.h"

struct RFrame;
struct RRtmpPacket;
struct AMediaCodec;
struct AMediaFormat;

class MediaCodecDequeueThread;

template<typename T>
class LinkedBlockingQueue;

class H264HwDecoder : public RThread
{
public:
    explicit H264HwDecoder();
    virtual ~H264HwDecoder();

    void setFrameQueue(LinkedBlockingQueue<RFrame *> *videoFrameQueue);
    void setPacketQueue(LinkedBlockingQueue<RRtmpPacket *> *queue);

    void release();

protected:
    void run() override;

private:
    bool decodeMetadata(RRtmpPacket *packet);
    void decodeFrame(RRtmpPacket *packet);
    void extractSpsPps(RRtmpPacket *packet, uint8_t **outSps, size_t *outSpsLen, uint8_t **outPps, size_t *outPpsLen);
    void reconfigure(int width, int height, uint8_t *sps, size_t spsLen, uint8_t *pps, size_t ppsLen);


private:
    LinkedBlockingQueue<RRtmpPacket *> *packetQueue;
    LinkedBlockingQueue<RFrame *> *renderQueue;

    MediaCodecDequeueThread *dequeueThread;

    int width;
    int height;

    AMediaCodec *mediaCodec;
    AMediaFormat *mediaFormat;

    std::atomic_bool released;
};


#endif //LIVEPLAYER_H264_HW_DECODER_H
