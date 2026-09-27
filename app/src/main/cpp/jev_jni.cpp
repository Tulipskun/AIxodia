#include <jni.h>
#include <android/log.h>
#include "laya.h"
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>
#include <thread>

#define LOG_TAG "AIxodiaJev"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct Runtime {
    laya_model * model = nullptr;
    laya_context * ctx = nullptr;
};

static std::vector<int32_t> build_ids(
        const laya_model * model,
        const std::string & goal,
        const std::vector<std::string> & options,
        std::vector<int32_t> & markers) {
    const int maxLen = std::max(64, laya_model_hparams(model).max_len);
    const int headMax = std::max(64, laya_model_hparams(model).head_max_len);
    std::vector<int32_t> ids;
    ids.push_back(laya_vocab_bos(model));

    const std::string head = "choice question: " + goal;
    auto headIds = laya_tokenize(model, head);
    ids.insert(ids.end(), headIds.begin(), headIds.end());
    ids.push_back(laya_vocab_sep(model));

    int budget = headMax;
    for (const auto & option : options) budget -= 1 + std::min<int>((int)laya_tokenize(model, " " + option).size(), 48);
    if (budget < 16 && !options.empty()) {
        budget = headMax;
    }

    for (const auto & option : options) {
        markers.push_back((int32_t)ids.size());
        ids.push_back(laya_vocab_mask(model));
        auto tok = laya_tokenize(model, " " + option);
        if ((int)tok.size() > 48) tok.resize(48);
        ids.insert(ids.end(), tok.begin(), tok.end());
    }
    ids.push_back(laya_vocab_sep(model));

    auto state = laya_tokenize(model, goal);
    int room = std::max(0, maxLen - (int)ids.size() - 1);
    if ((int)state.size() > room) state.resize(room);
    ids.insert(ids.end(), state.begin(), state.end());
    ids.push_back(laya_vocab_sep(model));
    if ((int)ids.size() > maxLen) ids.resize(maxLen);
    markers.erase(std::remove_if(markers.begin(), markers.end(), [maxLen](int p){ return p >= maxLen; }), markers.end());
    return ids;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tulipskun_aixodia_screen_LocalJevEngine_nativeOpen(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    Runtime *rt = new Runtime();
    try {
        rt->model = laya_model_load_from_file(p);
        rt->ctx = laya_init(rt->model, std::max(1, (int)std::thread::hardware_concurrency() / 2));
    } catch (...) {
        laya_free(rt->ctx);
        laya_model_free(rt->model);
        delete rt;
        env->ReleaseStringUTFChars(path, p);
        return 0;
    }
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(rt);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tulipskun_aixodia_screen_LocalJevEngine_nativeClose(JNIEnv *, jobject, jlong handle) {
    auto *rt = reinterpret_cast<Runtime *>(handle);
    if (!rt) return;
    laya_free(rt->ctx);
    laya_model_free(rt->model);
    delete rt;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tulipskun_aixodia_screen_LocalJevEngine_nativeChoose(
        JNIEnv *env, jobject, jlong handle, jstring goal, jobjectArray options) {
    auto *rt = reinterpret_cast<Runtime *>(handle);
    if (!rt || !rt->model || !rt->ctx) return env->NewStringUTF("");
    const char *g = env->GetStringUTFChars(goal, nullptr);
    std::vector<std::string> opts;
    const jsize n = env->GetArrayLength(options);
    for (jsize i = 0; i < n && i < 16; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(options, i);
        const char *s = env->GetStringUTFChars(js, nullptr);
        opts.emplace_back(s);
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }

    std::vector<int32_t> markers;
    auto ids = build_ids(rt->model, g, opts, markers);
    env->ReleaseStringUTFChars(goal, g);
    if (markers.empty()) return env->NewStringUTF("");

    std::vector<int32_t> positions(ids.size()), seqId(ids.size(), 0), qtype(ids.size(), 0);
    for (size_t i = 0; i < ids.size(); ++i) positions[i] = (int32_t)i;
    std::vector<int32_t> markerPos(16, 0), markerMask(16, 0), seqStart(1, 0);
    for (size_t i = 0; i < markers.size() && i < 16; ++i) {
        markerPos[i] = markers[i];
        markerMask[i] = 1;
    }
    laya_batch batch{};
    batch.n_tokens = (int32_t)ids.size();
    batch.n_seqs = 1;
    batch.tokens = ids.data();
    batch.positions = positions.data();
    batch.seq_id = seqId.data();
    batch.qtype = qtype.data();
    batch.marker_pos = markerPos.data();
    batch.marker_mask = markerMask.data();
    batch.seq_start = seqStart.data();

    laya_result result;
    if (laya_encode(rt->ctx, batch, result) != 0) return env->NewStringUTF("");

    const int k = std::min<int>((int)markers.size(), result.n_markers_max);
    float maxLogit = -INFINITY;
    for (int i = 0; i < k; ++i) maxLogit = std::max(maxLogit, result.logits[i]);
    float sum = 0.f;
    std::vector<float> p(k);
    for (int i = 0; i < k; ++i) { p[i] = std::exp(result.logits[i] - maxLogit); sum += p[i]; }
    int best = 0;
    for (int i = 0; i < k; ++i) { p[i] /= sum; if (p[i] > p[best]) best = i; }

    std::string out = std::to_string(best) + "|" + std::to_string(p[best]);
    return env->NewStringUTF(out.c_str());
}
