cmake_minimum_required(VERSION 3.22)
project(nymph-native C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# ---------------------------------------------------------------------------
# target 1：quickjs-ng（MIT，vendored 源码现场编译，纯 C）
# ---------------------------------------------------------------------------
add_library(quickjs-jni SHARED
    quickjs_jni.c
    quickjs/quickjs.c
)

target_include_directories(quickjs-jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(quickjs-jni PRIVATE _GNU_SOURCE)
target_link_libraries(quickjs-jni m)
if(ANDROID)
    target_link_libraries(quickjs-jni log)
    target_compile_options(quickjs-jni PRIVATE
        -ffunction-sections -fdata-sections
        -fvisibility=hidden
    )
    # 16 KB 页对齐（Android 15+ / Google Play targetSdk 35+ 硬性要求）。
    # NDK r27 需显式 linker flags；r28+ 才默认。缺了它 ELF LOAD 段停在
    # 0x1000(4KB)，16 KB 页设备 dlopen 直接失败。由 tools/check_elf_alignment.py
    # 发版实测核验，不靠构建参数自我声明。
    target_link_options(quickjs-jni PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,--gc-sections -Wl,--strip-all
    )
endif()

# ---------------------------------------------------------------------------
# target 2：ncnn JNI 桥（离线 HUD 轻量裁决模型 S4 支路，BSD-3-Clause）
#
# ncnn 以预编译共享库 vendor 在 jniLibs/<abi>/libncnn.so（见 third_party/VENDORED.md）。
# 用 IMPORTED 目标引用同一个 so：IMPORTED_NO_SONAME TRUE 让链接器写入 SONAME
# "libncnn.so" 而非绝对路径，so 由 jniLibs 统一打包一次，不重复拷贝。
# ---------------------------------------------------------------------------
if(ANDROID)
    set(NCNN_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so")

    if(EXISTS "${NCNN_LIB}")
        add_library(ncnn SHARED IMPORTED)
        set_target_properties(ncnn PROPERTIES
            IMPORTED_LOCATION "${NCNN_LIB}"
            IMPORTED_NO_SONAME TRUE
        )

        add_library(ncnn-jni SHARED ncnn_jni.cpp)
        target_include_directories(ncnn-jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
        target_link_libraries(ncnn-jni ncnn log android)
        target_compile_options(ncnn-jni PRIVATE
            -ffunction-sections -fdata-sections
            -fvisibility=hidden
            -fexceptions -frtti
        )
        target_link_options(ncnn-jni PRIVATE
            -Wl,-z,max-page-size=16384
            -Wl,-z,common-page-size=16384
            -Wl,--gc-sections -Wl,--strip-all
        )
    else()
        message(WARNING "libncnn.so not found at ${NCNN_LIB}; ncnn-jni target skipped")
    endif()
endif()
