package com.yongyi.rieszmagnifyandroid;

/**
 * Java wrappers for fast filters implemented in Halide.
 */
public class HalideFilters {
    // Load native Halide shared library.
    static {
        System.loadLibrary("native");
    }

    /**
     * Copy one YUV image to another. They must have the same size (but can
     * have different strides).
     *
     * @return true if it succeeded.
     */
    public static boolean copy(HalideYuvBufferT src, HalideYuvBufferT dst) {
        return HalideFilters.copyHalide(src.handle(), dst.handle());
    }

    /**
     * A Halide-accelerated native copy between two native Yuv handles.
     *
     * @return true if it succeeded.
     */
    private static native boolean copyHalide(long srcYuvHandle, long dstYuvHandle);

    /**
     * A Halide-accelerated Riesz magnifier on the luminance channel.
     *
     * @return true if it succeeded.
     */
    public static void magnify(HalideYuvBufferT src, HalideYuvBufferT dst, float preTranslateX, float preTranslateY, float theta) {
        HalideFilters.magnifyHalide(src.handle(), dst.handle(), preTranslateX, preTranslateY, theta);
    }

    /**
     * A Halide-accelerated Riesz magnifier on the luminance channel.
     *
     * @return true if it succeeded.
     */
    private static native void magnifyHalide(long srcYuvHandle, long dstYuvHandle, float preTranslateX, float preTranslateY, float theta);

    public static native void resetFilterHistory();

    public static native void setImageSize(int width, int height);

    public static native void setFilterCoefficients(double low, double high);

    public static native void setAmplification(float alpha);

    public static native void setTiling(int tileX, int tileY);

    public static native void setMeasuredFps(double averageFps);
}
