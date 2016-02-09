#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <vector>
#include <string>
#include <chrono>
#include <HalideRuntime.h>
#include "filter_util.h"
#include "magnify.h"
#include "YuvBufferT.h"
#include "YuvFunctions.h"
#include "copy.h"

#ifndef JNIEXPORT
#define JNIEXPORT  __attribute__ ((visibility ("default")))
#define JNICALL
typedef unsigned char jboolean;
#endif

#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG,"native",__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,"native",__VA_ARGS__)

using namespace std::chrono;

const int PYRAMID_LEVELS = 7;
std::vector<buffer_t> history{PYRAMID_LEVELS};
int mWidth, mHeight;
double mFps, mLowCutoff, mHighCutoff;
float mAlpha;
int mTileX, mTileY;
std::vector<double> filterA(3);
std::vector<double> filterB(3);

int scaleSize(int initialSize, int level)
{
    return initialSize / (int) pow(2.0, level);
}

void resetFilterHistory()
{
    LOGD("resetFilterHistory: mWidth = %d, mHeight = %d", mWidth, mHeight);
    for (int i = 0; i < PYRAMID_LEVELS; i++) {
        int w = scaleSize(mWidth, i), h = scaleSize(mHeight, i);
        int totalSize = w * h * 14;
        if (history[i].host) {
            memset(history[i].host, 0, sizeof(float) * totalSize);
        }
        else {
            history[i].host = (uint8_t * )(new float[totalSize]);
            memset(history[i].host, 0, sizeof(float) * totalSize);
            history[i].extent[0] = w;
            history[i].extent[1] = h;
            history[i].extent[2] = 7;   // Number of buffer types
            history[i].extent[3] = 2;   // Circular buffer size
            history[i].stride[0] = 1;
            history[i].stride[1] = w;
            history[i].stride[2] = w * h;
            history[i].stride[3] = w * h * 7;
            history[i].elem_size = 4;
        }
    }
}

void updateFilterCoefficients()
{
    // FPS should be at least twice the high cutoff. If it's not, then the resulting
    // output will be completely wrong.
    double fps = std::max(2 * mHighCutoff + 0.1, mFps);
    filter_util::computeFilter(fps, mLowCutoff, mHighCutoff, filterA, filterB);
}

extern "C"
{
// Extern functions from the Halide runtime that are not exposed in
// HalideRuntime.h.
int halide_host_cpu_count();

// The extern copy function required for the magnify pipeline.
int copyFloat32(int bufferType, int p, buffer_t *copyTo, buffer_t *in, buffer_t *out)
{
    if (in->host == nullptr || out->host == nullptr) {
        if (in->host == nullptr) {
            for (int i = 0; i < 2; i++) {
                in->min[i] = out->min[i];
                in->extent[i] = out->extent[i];
            }
        }
    }
    else {
        float *src = (float *) in->host;
        float *dst = (float *) out->host;
        float *dstCopy =
                (float *) copyTo->host + bufferType * copyTo->stride[2] + p * copyTo->stride[3];
        for (int y = out->min[1]; y < out->min[1] + out->extent[1]; y++) {
            float *srcLine = src + (y - in->min[1]) * in->stride[1];
            float *dstLine = dst + (y - out->min[1]) * out->stride[1];
            float *copyLine = dstCopy + y * copyTo->stride[1];
            memcpy(dstLine, srcLine + out->min[0] - in->min[0], sizeof(float) * out->extent[0]);
            memcpy(copyLine + out->min[0], srcLine + out->min[0] - in->min[0],
                   sizeof(float) * out->extent[0]);
        }
    }
    return 0;
}

JNIEXPORT jboolean JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_copyHalide(
        JNIEnv *env, jobject, long srcYuvBufferTHandle, long dstYuvBufferTHandle)
{
    if (srcYuvBufferTHandle == 0L || dstYuvBufferTHandle == 0L) {
        LOGE("copyHalide failed: src and dst must not be null");
        return false;
    }

    YuvBufferT *src = reinterpret_cast<YuvBufferT *>(srcYuvBufferTHandle);
    YuvBufferT *dst = reinterpret_cast<YuvBufferT *>(dstYuvBufferTHandle);

    if (!equalExtents(*src, *dst)) {
        LOGE("copyHalide failed: src and dst extents must be equal.\n\t"
                     "src extents: luma: %d, %d, chromaU: %d, %d, chromaV: %d, %d.\n\t"
                     "dst extents: luma: %d, %d, chromaU: %d, %d, chromaV: %d, %d.",
             src->luma().extent[0], src->luma().extent[1],
             src->chromaU().extent[0], src->chromaU().extent[1],
             src->chromaV().extent[0], src->chromaV().extent[1],
             dst->luma().extent[0], dst->luma().extent[1],
             dst->chromaU().extent[0], dst->chromaU().extent[1],
             dst->chromaV().extent[0], dst->chromaV().extent[1]);
        return false;
    }

    YuvBufferT::ChromaStorage srcChromaStorage = src->chromaStorage();
    YuvBufferT::ChromaStorage dstChromaStorage = dst->chromaStorage();

    return copy2D(*src, *dst);
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_magnifyHalide(
        JNIEnv *env, jobject, long srcYuvBufferTHandle, long dstYuvBufferTHandle,
        float preTranslateX, float preTranslateY, float theta)
{
    if (srcYuvBufferTHandle == 0L || dstYuvBufferTHandle == 0L) {
        LOGE("magnifyHalide failed: src and dst must not be null!");
        return;
    }

    YuvBufferT *src = reinterpret_cast<YuvBufferT *>(srcYuvBufferTHandle);
    YuvBufferT *dst = reinterpret_cast<YuvBufferT *>(dstYuvBufferTHandle);

    static bool first_call = true;
    static unsigned counter = 0;
    static double times[16]{};
    if (first_call) {
        LOGD("According to Halide, host system has %d cpus\n", halide_host_cpu_count());
        resetFilterHistory();
        first_call = false;
    }

    auto t1 = high_resolution_clock::now();
    copy(&src->chromaU(), (int) (preTranslateX / 2), (int) (preTranslateY / 2), theta, 40, 40,
         &dst->chromaU());
    copy(&src->chromaV(), (int) (preTranslateX / 2), (int) (preTranslateY / 2), theta, 40, 40,
         &dst->chromaV());
    magnify(&src->luma(), (float) filterA[1], (float) filterA[2],
            (float) filterB[0], (float) filterB[1], (float) filterB[2],
            mAlpha,
            counter % 2, (int) preTranslateX, (int) preTranslateY, theta, mTileX, mTileY,
            &history[0], &history[1], &history[2], &history[3], &history[4], &history[5],
            &history[6],
            &dst->luma());

    auto t2 = high_resolution_clock::now();
    long long elapsedNs = duration_cast<nanoseconds>(t2 - t1).count();
    double elapsed = (double) elapsedNs / 1000000000;
    times[counter & 15] = elapsed;
    counter++;
    double min = *std::min_element(std::begin(times), std::end(times));
    if (counter % 16 == 0) {
        LOGD("Magnify took %f s (minimum: %f s = %f fps)", elapsed, min, 1.0 / min);
        LOGD("Settings: alpha = %f, fps = %f, mLowCutoff = %f, mHighCutoff = %f",
             mAlpha, mFps, mLowCutoff, mHighCutoff);
    }
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_setImageSize(
        JNIEnv *env, jobject, int width, int height)
{
    LOGD("Setting width = %d, height = %d", width, height);
    mWidth = width;
    mHeight = height;
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_setAmplification(
        JNIEnv *env, jobject, float alpha)
{
    LOGD("Setting alpha = %f", alpha);
    mAlpha = alpha;
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_setTiling(
        JNIEnv *env, jobject, int tileX, int tileY)
{
    LOGD("Setting tile parameters, tileX = %d, tileY = %d", tileX, tileY);
    mTileX = tileX;
    mTileY = tileY;
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_resetFilterHistory(
        JNIEnv *env, jobject)
{
    resetFilterHistory();
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_setFilterCoefficients(
        JNIEnv *env, jobject, double low, double high)
{
    LOGD("Setting low = %f, high = %f", low, high);
    mLowCutoff = low;
    mHighCutoff = high;
    updateFilterCoefficients();
}

JNIEXPORT void JNICALL Java_com_yongyi_rieszmagnifyandroid_HalideFilters_setMeasuredFps(
        JNIEnv *env, jobject, double measuredFps)
{
    LOGD("Setting measuredFps = %f", measuredFps);
    mFps = measuredFps;
    updateFilterCoefficients();
}
}
