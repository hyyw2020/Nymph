/*
 * NymphAuto × quickjs-ng JNI 绑定。
 *
 * 对应 Kotlin 类 dev.nymph.auto.runner.QuickJsNative 的 external 方法：
 *   create(callback) / destroy / eval / setVar / setGlobal / setVarsObject
 *   / syncVars / reset / interrupt
 *
 * 设计约定：
 * - 每个 create() 返回一个独立 JSRuntime + JSContext 指针（opaque jlong），
 *   上下文之间完全隔离；reset() 重建 JSContext 得到干净全局环境。
 * - eval() 返回值协议：成功 → 结果值的 JSON 文本（undefined → 空串）；
 *   异常 → {"__nymph_error__":"<message>"}。
 * - JS 侧全局约定（create 时注入 prelude）：
 *     vars      —— 变量表（JS 对象，eval 前由 Kotlin 推入，eval 后可 syncVars 回读）
 *     zdjl.*    —— 自动精灵兼容命名空间（log/toast/sleep/findImage/findText/
 *                  check/deviceInfo），全部经 __nymphCallNative 上调 Kotlin 回调
 * - 单线程使用约定：同一 context 的 JNI 方法必须从同一线程调用（执行器串行调度）；
 *   interrupt() 可从其他线程调用，内部用原子标志 + JS_SetInterruptHandler 实现
 *   指令级中断（停止按钮语义）。
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "quickjs/quickjs.h"

#define NymphCtxSig 0x4E594D43u /* 'NYMC' */

typedef struct {
    uint32_t sig;
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *vm;
    jobject cb;        /* QuickJsNative 单例（全局引用） */
    jmethodID onCall;  /* String onCall(String method, String argsJson) */
    JNIEnv *env;       /* 最近一次 JNI 进入时的 env（同线程复用） */
    volatile int interrupt;
} NymphCtx;

/* ---------- 工具 ---------- */

static NymphCtx *nc_of(jlong ptr) {
    if (ptr == 0) return NULL;
    NymphCtx *nc = (NymphCtx *)(intptr_t)ptr;
    return nc->sig == NymphCtxSig ? nc : NULL;
}

/* 将任意 C 字符串转义为 JSON 字符串字面量（含引号）。调用方 free。 */
static char *json_escape(const char *s) {
    size_t n = strlen(s);
    /* 最坏情况每字符 6 字节（\u00XX） */
    char *out = (char *)malloc(n * 6 + 3);
    if (!out) return NULL;
    char *p = out;
    *p++ = '"';
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        switch (c) {
            case '"':  *p++ = '\\'; *p++ = '"'; break;
            case '\\': *p++ = '\\'; *p++ = '\\'; break;
            case '\b': *p++ = '\\'; *p++ = 'b'; break;
            case '\f': *p++ = '\\'; *p++ = 'f'; break;
            case '\n': *p++ = '\\'; *p++ = 'n'; break;
            case '\r': *p++ = '\\'; *p++ = 'r'; break;
            case '\t': *p++ = '\\'; *p++ = 't'; break;
            default:
                if (c < 0x20) {
                    p += sprintf(p, "\\u%04x", c);
                } else {
                    *p++ = (char)c; /* UTF-8 原样透传 */
                }
        }
    }
    *p++ = '"';
    *p = '\0';
    return out;
}

/* JS 值 → JSON 文本。undefined/symbol → NULL；异常 → NULL（*exc=1）。 */
static char *js_to_json(JSContext *ctx, JSValue val, int *exc) {
    *exc = 0;
    if (JS_IsException(val)) { *exc = 1; return NULL; }
    if (JS_IsUndefined(val) || JS_VALUE_GET_TAG(val) == JS_TAG_SYMBOL) return NULL;
    JSValue s = JS_JSONStringify(ctx, val, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(s)) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        *exc = 1;
        return NULL;
    }
    const char *cs = JS_ToCString(ctx, s);
    char *out = cs ? strdup(cs) : NULL;
    if (cs) JS_FreeCString(ctx, cs);
    JS_FreeValue(ctx, s);
    return out;
}

/* 异常值 → 人类可读信息（优先 message + stack）。 */
static char *exc_to_text(JSContext *ctx) {
    JSValue exc = JS_GetException(ctx);
    const char *raw = JS_ToCString(ctx, exc);
    char *esc = raw ? json_escape(raw) : NULL;
    if (raw) JS_FreeCString(ctx, raw);
    JS_FreeValue(ctx, exc);
    return esc; /* 已含引号的 JSON 字符串 */
}

/* ---------- __nymphCallNative 原生函数（JS → Kotlin 上调） ---------- */

static JSValue js_nymph_call_native(JSContext *ctx, JSValueConst this_val,
                                    int argc, JSValueConst *argv) {
    NymphCtx *nc = (NymphCtx *)JS_GetContextOpaque(ctx);
    if (!nc || !nc->cb || !nc->env) return JS_UNDEFINED;
    if (argc < 2) return JS_UNDEFINED;

    const char *method = JS_ToCString(ctx, argv[0]);
    const char *argsJson = JS_ToCString(ctx, argv[1]);
    if (!method || !argsJson) {
        if (method) JS_FreeCString(ctx, method);
        if (argsJson) JS_FreeCString(ctx, argsJson);
        return JS_UNDEFINED;
    }

    JNIEnv *env = nc->env;
    JSValue result = JS_UNDEFINED;
    jstring jm = (*env)->NewStringUTF(env, method);
    jstring ja = (*env)->NewStringUTF(env, argsJson);
    if (jm && ja) {
        jstring jr = (jstring)(*env)->CallObjectMethod(env, nc->cb, nc->onCall, jm, ja);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        } else if (jr) {
            const char *rres = (*env)->GetStringUTFChars(env, jr, NULL);
            if (rres) {
                JSValue parsed = JS_ParseJSON(ctx, rres, strlen(rres), "<upcall>");
                if (!JS_IsException(parsed)) {
                    result = parsed;
                } else {
                    JS_FreeValue(ctx, JS_GetException(ctx));
                }
                (*env)->ReleaseStringUTFChars(env, jr, rres);
            }
            (*env)->DeleteLocalRef(env, jr);
        }
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, jm);
    (*env)->DeleteLocalRef(env, ja);
    JS_FreeCString(ctx, method);
    JS_FreeCString(ctx, argsJson);
    return result;
}

/* ---------- 中断处理（停止按钮） ---------- */

static int nymph_interrupt_handler(JSContext *ctx, void *opaque) {
    (void)ctx;
    NymphCtx *nc = (NymphCtx *)opaque;
    return __atomic_load_n(&nc->interrupt, __ATOMIC_SEQ_CST) ? 1 : 0;
}

/* ---------- prelude：vars / zdjl 兼容命名空间 ---------- */

static const char *PRELUDE =
"(function(){\n"
"  globalThis.__nymphCall = function(method, args){\n"
"    return globalThis.__nymphCallNative(method, JSON.stringify(args));\n"
"  };\n"
"  globalThis.zdjl = {\n"
"    log:   function(m){ __nymphCall('log', [String(m)]); },\n"
"    toast: function(m){ __nymphCall('toast', [String(m)]); },\n"
"    sleep: function(ms){ __nymphCall('sleep', [Number(ms)]); },\n"
"    findImage: function(b64){ return __nymphCall('findImage', [String(b64)]); },\n"
"    findText:  function(t){ return __nymphCall('findText', [String(t)]); },\n"
"    check:     function(n){ return __nymphCall('check', [String(n)]); },\n"
"    deviceInfo:function(){ return __nymphCall('deviceInfo', []); }\n"
"  };\n"
"  Object.defineProperty(globalThis.zdjl, 'vars', {\n"
"    get: function(){ return globalThis.vars; }\n"
"  });\n"
"  globalThis.vars = {};\n"
"})();";

static int install_prelude(NymphCtx *nc) {
    JSValue g = JS_GetGlobalObject(nc->ctx);
    JSValue fn = JS_NewCFunction(nc->ctx, js_nymph_call_native, "__nymphCallNative", 2);
    if (JS_IsException(fn)) {
        JS_FreeValue(nc->ctx, JS_GetException(nc->ctx));
        JS_FreeValue(nc->ctx, g);
        return -1;
    }
    /* JS_SetPropertyStr 消费 fn */
    JS_SetPropertyStr(nc->ctx, g, "__nymphCallNative", fn);
    JS_FreeValue(nc->ctx, g);

    JSValue r = JS_Eval(nc->ctx, PRELUDE, strlen(PRELUDE), "<prelude>",
                        JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        JS_FreeValue(nc->ctx, JS_GetException(nc->ctx));
        return -1;
    }
    JS_FreeValue(nc->ctx, r);
    return 0;
}

/* ---------- JNI 入口 ---------- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_create(JNIEnv *env, jclass clazz,
                                                jobject callback) {
    (void)clazz;
    NymphCtx *nc = (NymphCtx *)calloc(1, sizeof(NymphCtx));
    if (!nc) return 0;
    nc->sig = NymphCtxSig;
    nc->rt = JS_NewRuntime();
    if (!nc->rt) { free(nc); return 0; }
    JS_SetMemoryLimit(nc->rt, 64u * 1024u * 1024u);
    nc->ctx = JS_NewContext(nc->rt);
    if (!nc->ctx) { JS_FreeRuntime(nc->rt); free(nc); return 0; }
    JS_SetContextOpaque(nc->ctx, nc);

    if ((*env)->GetJavaVM(env, &nc->vm) != JNI_OK) {
        JS_FreeContext(nc->ctx);
        JS_FreeRuntime(nc->rt);
        free(nc);
        return 0;
    }
    nc->env = env;
    __atomic_store_n(&nc->interrupt, 0, __ATOMIC_SEQ_CST);
    JS_SetInterruptHandler(nc->rt, nymph_interrupt_handler, nc);

    if (callback) {
        nc->cb = (*env)->NewGlobalRef(env, callback);
        jclass cbCls = (*env)->GetObjectClass(env, callback);
        nc->onCall = (*env)->GetMethodID(env, cbCls, "onCall",
                                         "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        (*env)->DeleteLocalRef(env, cbCls);
        if (!nc->onCall) {
            if (nc->cb) (*env)->DeleteGlobalRef(env, nc->cb);
            JS_FreeContext(nc->ctx);
            JS_FreeRuntime(nc->rt);
            free(nc);
            return 0;
        }
    }

    if (install_prelude(nc) != 0) {
        if (nc->cb) (*env)->DeleteGlobalRef(env, nc->cb);
        JS_FreeContext(nc->ctx);
        JS_FreeRuntime(nc->rt);
        free(nc);
        return 0;
    }
    return (jlong)(intptr_t)nc;
}

JNIEXPORT void JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_destroy(JNIEnv *env, jclass clazz,
                                                 jlong ptr) {
    (void)env; (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc) return;
    if (nc->cb && nc->vm) {
        JNIEnv *e = NULL;
        if ((*nc->vm)->GetEnv(nc->vm, (void **)&e, JNI_VERSION_1_6) == JNI_OK && e) {
            (*e)->DeleteGlobalRef(e, nc->cb);
        }
    }
    nc->sig = 0;
    JS_FreeContext(nc->ctx);
    JS_FreeRuntime(nc->rt);
    free(nc);
}

/* 执行 JS。成功返回结果 JSON；异常返回 {"__nymph_error__":"..."}。 */
JNIEXPORT jstring JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_eval(JNIEnv *env, jclass clazz,
                                              jlong ptr, jstring code) {
    (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc || !code) return (*env)->NewStringUTF(env, "{\"__nymph_error__\":\"context gone\"}");
    nc->env = env;
    __atomic_store_n(&nc->interrupt, 0, __ATOMIC_SEQ_CST);

    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    if (!src) return (*env)->NewStringUTF(env, "{\"__nymph_error__\":\"bad source\"}");
    size_t len = strlen(src);

    JSValue result = JS_Eval(nc->ctx, src, len, "<nymph>", JS_EVAL_TYPE_GLOBAL);

    /* 跑完 pending job（Promise 微任务） */
    if (!JS_IsException(result)) {
        JSContext *pctx = NULL;
        while (JS_ExecutePendingJob(nc->rt, &pctx) > 0) {
        }
    }

    jstring out;
    if (JS_IsException(result)) {
        char *esc = exc_to_text(nc->ctx);
        if (esc) {
            size_t n = strlen(esc) + 32;
            char *buf = (char *)malloc(n);
            if (buf) {
                snprintf(buf, n, "{\"__nymph_error__\":%s}", esc);
                out = (*env)->NewStringUTF(env, buf);
                free(buf);
            } else {
                out = (*env)->NewStringUTF(env, "{\"__nymph_error__\":\"oom\"}");
            }
            free(esc);
        } else {
            out = (*env)->NewStringUTF(env, "{\"__nymph_error__\":\"unknown\"}");
        }
    } else {
        int exc = 0;
        char *json = js_to_json(nc->ctx, result, &exc);
        JS_FreeValue(nc->ctx, result);
        if (exc) {
            out = (*env)->NewStringUTF(env, "{\"__nymph_error__\":\"stringify failed\"}");
        } else if (!json) {
            out = (*env)->NewStringUTF(env, ""); /* undefined → 空串 */
        } else {
            out = (*env)->NewStringUTF(env, json);
            free(json);
        }
    }
    (*env)->ReleaseStringUTFChars(env, code, src);
    return out;
}

/* 解析 valueJson 为 JSValue；解析失败退化为 null。 */
static JSValue parse_json_arg(NymphCtx *nc, JNIEnv *env, jstring valueJson) {
    if (!valueJson) return JS_NULL;
    const char *s = (*env)->GetStringUTFChars(env, valueJson, NULL);
    if (!s) return JS_NULL;
    JSValue v = JS_ParseJSON(nc->ctx, s, strlen(s), "<arg>");
    if (JS_IsException(v)) {
        JS_FreeValue(nc->ctx, JS_GetException(nc->ctx));
        v = JS_NULL;
    }
    (*env)->ReleaseStringUTFChars(env, valueJson, s);
    return v;
}

/* 确保 globalThis.vars 是对象。 */
static JSValue get_vars_object(NymphCtx *nc) {
    JSValue g = JS_GetGlobalObject(nc->ctx);
    JSValue vars = JS_GetPropertyStr(nc->ctx, g, "vars");
    if (JS_IsUndefined(vars) || !JS_IsObject(vars)) {
        if (!JS_IsUndefined(vars)) JS_FreeValue(nc->ctx, vars);
        vars = JS_NewObject(nc->ctx);
        JS_SetPropertyStr(nc->ctx, g, "vars", vars); /* 消费 */
        vars = JS_GetPropertyStr(nc->ctx, g, "vars");
    }
    JS_FreeValue(nc->ctx, g);
    return vars;
}

JNIEXPORT void JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_setVar(JNIEnv *env, jclass clazz,
                                                jlong ptr, jstring name,
                                                jstring valueJson) {
    (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc || !name) return;
    nc->env = env;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    if (!n) return;
    JSValue v = parse_json_arg(nc, env, valueJson);
    JSValue vars = get_vars_object(nc);
    JS_SetPropertyStr(nc->ctx, vars, n, v); /* 消费 v */
    JS_FreeValue(nc->ctx, vars);
    (*env)->ReleaseStringUTFChars(env, name, n);
}

JNIEXPORT void JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_setGlobal(JNIEnv *env, jclass clazz,
                                                   jlong ptr, jstring name,
                                                   jstring valueJson) {
    (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc || !name) return;
    nc->env = env;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    if (!n) return;
    JSValue v = parse_json_arg(nc, env, valueJson);
    JSValue g = JS_GetGlobalObject(nc->ctx);
    JS_SetPropertyStr(nc->ctx, g, n, v); /* 消费 v */
    JS_FreeValue(nc->ctx, g);
    (*env)->ReleaseStringUTFChars(env, name, n);
}

/* 批量：整体替换 globalThis.vars（Kotlin 侧序列化整个变量表）。 */
JNIEXPORT void JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_setVarsObject(JNIEnv *env, jclass clazz,
                                                       jlong ptr, jstring mapJson) {
    (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc) return;
    nc->env = env;
    JSValue v = parse_json_arg(nc, env, mapJson);
    if (!JS_IsObject(v)) {
        JS_FreeValue(nc->ctx, v);
        v = JS_NewObject(nc->ctx);
    }
    JSValue g = JS_GetGlobalObject(nc->ctx);
    JS_SetPropertyStr(nc->ctx, g, "vars", v); /* 消费 v */
    JS_FreeValue(nc->ctx, g);
}

/* 回读 vars（JSON）。脚本对 vars 的写操作经此同步回 Kotlin 变量表。 */
JNIEXPORT jstring JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_syncVars(JNIEnv *env, jclass clazz,
                                                  jlong ptr) {
    (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc) return (*env)->NewStringUTF(env, "{}");
    nc->env = env;
    JSValue g = JS_GetGlobalObject(nc->ctx);
    JSValue vars = JS_GetPropertyStr(nc->ctx, g, "vars");
    JS_FreeValue(nc->ctx, g);
    int exc = 0;
    char *json = js_to_json(nc->ctx, vars, &exc);
    JS_FreeValue(nc->ctx, vars);
    if (exc || !json) return (*env)->NewStringUTF(env, "{}");
    jstring out = (*env)->NewStringUTF(env, json);
    free(json);
    return out;
}

/* 重置：重建 JSContext，全局环境回到 prelude 初始状态。 */
JNIEXPORT void JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_reset(JNIEnv *env, jclass clazz,
                                               jlong ptr) {
    (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc) return;
    nc->env = env;
    JS_FreeContext(nc->ctx);
    nc->ctx = JS_NewContext(nc->rt);
    if (!nc->ctx) return; /* 运行期不应发生；下一次调用会报 context gone */
    JS_SetContextOpaque(nc->ctx, nc);
    JS_SetMemoryLimit(nc->rt, 64u * 1024u * 1024u);
    install_prelude(nc);
}

/* 请求中断当前执行的 JS（指令级检查点）。 */
JNIEXPORT void JNICALL
Java_dev_nymph_auto_runner_QuickJsNative_interrupt(JNIEnv *env, jclass clazz,
                                                   jlong ptr) {
    (void)env; (void)clazz;
    NymphCtx *nc = nc_of(ptr);
    if (!nc) return;
    __atomic_store_n(&nc->interrupt, 1, __ATOMIC_SEQ_CST);
}
