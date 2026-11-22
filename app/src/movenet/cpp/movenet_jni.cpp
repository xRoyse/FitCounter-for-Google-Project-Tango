#include <jni.h>
#include <memory>
#include "tensorflow/lite/c/c_api.h"

namespace {
constexpr int kInputSize = 192 * 192 * 3;
constexpr int kOutputSize = 17 * 3;

struct State {
  TfLiteModel* model = nullptr;
  TfLiteInterpreter* interpreter = nullptr;

  ~State() {
    if (interpreter) TfLiteInterpreterDelete(interpreter);
    if (model) TfLiteModelDelete(model);
  }
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_fitcounter_MoveNetEstimator_create(JNIEnv* env, jclass, jobject weights) {
  auto state = std::make_unique<State>();
  const void* data = env->GetDirectBufferAddress(weights);
  jlong size = env->GetDirectBufferCapacity(weights);
  if (!data || size <= 0) return 0;

  state->model = TfLiteModelCreate(data, size);
  if (!state->model) return 0;
  TfLiteInterpreterOptions* options = TfLiteInterpreterOptionsCreate();
  if (!options) return 0;
  TfLiteInterpreterOptionsSetNumThreads(options, 2);
  state->interpreter = TfLiteInterpreterCreate(state->model, options);
  TfLiteInterpreterOptionsDelete(options);
  if (!state->interpreter || TfLiteInterpreterAllocateTensors(state->interpreter) != kTfLiteOk) return 0;

  const auto* input = TfLiteInterpreterGetInputTensor(state->interpreter, 0);
  const auto* output = TfLiteInterpreterGetOutputTensor(state->interpreter, 0);
  if (!input || !output || TfLiteTensorType(input) != kTfLiteUInt8 ||
      TfLiteTensorByteSize(input) != kInputSize || TfLiteTensorType(output) != kTfLiteFloat32 ||
      TfLiteTensorByteSize(output) != kOutputSize * sizeof(float)) return 0;
  return reinterpret_cast<jlong>(state.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_fitcounter_MoveNetEstimator_run(JNIEnv* env, jclass, jlong handle,
                                                 jobject rgb, jfloatArray result) {
  auto* state = reinterpret_cast<State*>(handle);
  const void* pixels = env->GetDirectBufferAddress(rgb);
  if (!state || !pixels || env->GetDirectBufferCapacity(rgb) != kInputSize ||
      env->GetArrayLength(result) != kOutputSize) return JNI_FALSE;

  auto* interpreter = state->interpreter;
  auto* input = TfLiteInterpreterGetInputTensor(interpreter, 0);
  if (TfLiteTensorCopyFromBuffer(input, pixels, kInputSize) != kTfLiteOk ||
      TfLiteInterpreterInvoke(interpreter) != kTfLiteOk) return JNI_FALSE;

  float joints[kOutputSize];
  const auto* output = TfLiteInterpreterGetOutputTensor(interpreter, 0);
  if (TfLiteTensorCopyToBuffer(output, joints, sizeof(joints)) != kTfLiteOk) return JNI_FALSE;
  env->SetFloatArrayRegion(result, 0, kOutputSize, joints);
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_fitcounter_MoveNetEstimator_destroy(JNIEnv*, jclass, jlong handle) {
  delete reinterpret_cast<State*>(handle);
}
