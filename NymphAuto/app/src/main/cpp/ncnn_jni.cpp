/*
 * NymphAuto × ncnn 20261020 JNI 桥接（离线 HUD 轻量裁决模型，S4 支路）。
 *
 * 对应 Kotlin 类 dev.nymph.auto.vision.NcnnNative 的 external 方法：
 *   nativeLoad(paramPath, binPath) -> handle     加载 .param/.bin（单线程常驻）
 *   nativeClassify(handle, rgba, w, h) -> FloatArray  对 96×96 crop 分类
 *   nativeClose(handle)                           释放 Net
 *
 * 设计约定（对应方案的工程注意）：
 * - CompiledModel/Net 由单一后台线程独占：创建、推理、关闭都在 Kotlin 侧串行 executor 上调用，
 *   本文件不做线程切换；不在主线程加载。
 * - buffer 复用由 Kotlin 侧持有 FloatArray；本文件只借指针，不跨调用持有。
 * - 模型文件来自 assets/（打包进 APK）或 filesDir/（本地迁移），不联网、不热更。
 * - 无模型 / 加载失败时 nativeLoad 返回 0，Kotlin 侧据此 fail-closed 降级到纯 CV（V1）主干。
 *
 * 许可证：本文件 MIT；依赖的 ncnn 为 BSD-3-Clause（见 third_party/ncnn/LICENSE）。
 */

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <new>

#include "vendor/ncnn/include/ncnn/net.h"
#include "vendor/ncnn/include/ncnn/mat.h"

#define NYMPH_NCNN_SIG 0x4E4E434Eu  /* 'NNCN' */

extern "C" {

/**
 * 加载模型。成功返回带签名的 opaque 句柄（jlong），失败返回 0。
 * 只用 CPU 路径：HUD 分类模型极小（96×96 输入），CPU 足够且数值稳定可复现；
 * Vulkan 仅在同一模型需要大输入时才由 use_vulkan 打开，本默认关闭。
 */
JNIEXPORT jlong JNICALL
Java_dev_nymph_auto_vision_NcnnNative_nativeLoad(JNIEnv *env, jclass, jstring jParam, jstring jBin, jint useVulkan) {
    if (jParam == nullptr || jBin == nullptr) return 0;
    const char *param = env->GetStringUTFChars(jParam, nullptr);
    const char *bin = env->GetStringUTFChars(jBin, nullptr);
    if (param == nullptr || bin == nullptr) {
        if (param) env->ReleaseStringUTFChars(jParam, param);
        if (bin) env->ReleaseStringUTFChars(jBin, bin);
        return 0;
    }
    ncnn::Net *net = new (std::nothrow) ncnn::Net();
    net->opt.num_threads = 4;          // 预算：CPU 4 线程
    net->opt.use_vulkan_compute = (useVulkan != 0);
    int rc = net->load_param(param);
    if (rc == 0) rc = net->load_model(bin);
    env->ReleaseStringUTFChars(jParam, param);
    env->ReleaseStringUTFChars(jBin, bin);
    if (rc != 0) {
        delete net;
        return 0;
    }
    struct Holder {
        uint32_t sig;
        ncnn::Net *net;
    };
    Holder *h = new (std::nothrow) Holder{NYMPH_NCNN_SIG, net};
    return reinterpret_cast<jlong>(h);
}

/**
 * 对一段 RGBA 像素做分类，返回各类别置信度（FloatArray）。
 * 输入约定：w=h=96，rgba 长度 = w*h*4；归一化 (v/255-0.5)/0.5，CHW 排布。
 * 返回的数组内容由调用方按 labels.txt 顺序解释；分类结果只作为"是什么"，不产生坐标。
 */
JNIEXPORT jfloatArray JNICALL
Java_dev_nymph_auto_vision_NcnnNative_nativeClassify(
        JNIEnv *env, jclass, jlong handle, jbyteArray jRgba, jint w, jint h) {
    if (handle == 0 || jRgba == nullptr || w <= 0 || h <= 0) return nullptr;
    // 句柄校验：结构与 nativeLoad 内 Holder 布局一致（仅签名 + net 指针）。
    struct Holder { uint32_t sig; ncnn::Net *net; };
    Holder *hd = reinterpret_cast<Holder *>(static_cast<intptr_t>(handle));
    if (hd->sig != NYMPH_NCNN_SIG || hd->net == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(jRgba);
    if (n < w * h * 4) return nullptr;
    jbyte *rgba = env->GetByteArrayElements(jRgba, nullptr);
    if (rgba == nullptr) return nullptr;

    ncnn::Mat in(w, h, 3);  // 3 通道（CV_32F），逐像素填充
    float *out0 = in.channel(0), *out1 = in.channel(1), *out2 = in.channel(2);
    for (int i = 0; i < w * h; ++i) {
        int p = i * 4;
        out0[i] = (((unsigned char)rgba[p] / 255.0f) - 0.5f) / 0.5f;
        out1[i] = (((unsigned char)rgba[p + 1] / 255.0f) - 0.5f) / 0.5f;
        out2[i] = (((unsigned char)rgba[p + 2] / 255.0f) - 0.5f) / 0.5f;
    }
    env->ReleaseByteArrayElements(jRgba, rgba, JNI_ABORT);

    ncnn::Extractor ex = hd->net->create_extractor();
    if (ex.input("in0", in) != 0) return nullptr;
    ncnn::Mat prob;
    if (ex.extract("out0", prob) != 0) return nullptr;

    jsize cls = static_cast<jsize>(prob.total());
    jfloatArray result = env->NewFloatArray(cls);
    if (result == nullptr) return nullptr;
    // ncnn 20261020 的 Mat 以裸 data(void*) 暴露连续存储；输出为单通道 float，
    // total() 即元素数。用 operator[] 逐元素读，兼容 elempack 布局。
    if (prob.data == nullptr) return nullptr;
    float *values = new (std::nothrow) float[cls];
    if (values == nullptr) return nullptr;
    for (jsize i = 0; i < cls; ++i) values[i] = prob[i];
    env->SetFloatArrayRegion(result, 0, cls, values);
    delete[] values;
    return result;
}

/** 释放模型句柄。重复关闭按 no-op 处理（签名已清）。 */
JNIEXPORT void JNICALL
Java_dev_nymph_auto_vision_NcnnNative_nativeClose(JNIEnv *, jclass, jlong handle) {
    if (handle == 0) return;
    struct Holder { uint32_t sig; ncnn::Net *net; };
    Holder *hd = reinterpret_cast<Holder *>(static_cast<intptr_t>(handle));
    if (hd->sig != NYMPH_NCNN_SIG) return;
    hd->sig = 0;
    delete hd->net;
    hd->net = nullptr;
    delete hd;
}

/** 能力探测：库是否成功加载（Kotlin 侧据此决定 V2 支路是否可用）。 */
JNIEXPORT jboolean JNICALL
Java_dev_nymph_auto_vision_NcnnNative_nativeAvailable(JNIEnv *, jclass) {
    return JNI_TRUE;
}

}  // extern "C"
