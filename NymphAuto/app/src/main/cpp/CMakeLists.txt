cmake_minimum_required(VERSION 3.22)
project(nymph-quickjs-jni C)

set(CMAKE_C_STANDARD 11)

# quickjs-ng amalgamation（MIT，见 quickjs/LICENSE）+ NymphAuto JNI 桥
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
    # 16 KB 页对齐（Android 15+ 设备 / Google Play 自 2025-11-01 对 targetSdk 15+
    # 的硬性要求）。本工程用 NDK r27，官方要求显式加 linker flags（r28+ 才默认）。
    # 缺了它，ELF 的 LOAD 段 Align 停在 0x1000(4KB)，在 16 KB 页设备上 dlopen
    # 会直接失败。另见 build.gradle.kts 的 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    # 与 tools/check_elf_alignment.py 的发版核验。
    target_link_options(quickjs-jni PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384
        -Wl,--gc-sections -Wl,--strip-all
    )
endif()
