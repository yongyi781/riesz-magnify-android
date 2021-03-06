#ifndef HALIDE__magnify_h
#define HALIDE__magnify_h
#ifndef HALIDE_ATTRIBUTE_ALIGN
  #ifdef _MSC_VER
    #define HALIDE_ATTRIBUTE_ALIGN(x) __declspec(align(x))
  #else
    #define HALIDE_ATTRIBUTE_ALIGN(x) __attribute__((aligned(x)))
  #endif
#endif
#ifndef BUFFER_T_DEFINED
#define BUFFER_T_DEFINED
#include <stdbool.h>
#include <stdint.h>
typedef struct buffer_t {
    uint64_t dev;
    uint8_t* host;
    int32_t extent[4];
    int32_t stride[4];
    int32_t min[4];
    int32_t elem_size;
    HALIDE_ATTRIBUTE_ALIGN(1) bool host_dirty;
    HALIDE_ATTRIBUTE_ALIGN(1) bool dev_dirty;
    HALIDE_ATTRIBUTE_ALIGN(1) uint8_t _padding[10 - sizeof(void *)];
} buffer_t;
#endif
struct halide_filter_metadata_t;
#ifndef HALIDE_FUNCTION_ATTRS
#define HALIDE_FUNCTION_ATTRS
#endif
#ifdef __cplusplus
extern "C" {
#endif
int magnify(const buffer_t *_input_buffer, const float _a1, const float _a2, const float _b0, const float _b1, const float _b2, const float _alpha, const int32_t _pParam, const int32_t _preTranslateX, const int32_t _preTranslateY, const float _rotationTheta, const int32_t _tileFactorX, const int32_t _tileFactorY, buffer_t *_historyBuffer0_buffer, buffer_t *_historyBuffer1_buffer, buffer_t *_historyBuffer2_buffer, buffer_t *_historyBuffer3_buffer, buffer_t *_historyBuffer4_buffer, buffer_t *_historyBuffer5_buffer, buffer_t *_historyBuffer6_buffer, const buffer_t *_output_buffer) HALIDE_FUNCTION_ATTRS;
int magnify_argv(void **args) HALIDE_FUNCTION_ATTRS;
extern const struct halide_filter_metadata_t magnify_metadata;
#ifdef __cplusplus
}  // extern "C"
#endif
#endif
