# quickjs-ng（vendored）

源码来自 https://github.com/quickjs-ng/quickjs （MIT License，见 `LICENSE`）。

`quickjs.c` 为由 `dtoa.c / libregexp.c / libunicode.c / quickjs.c` 四个源文件
按官方 `amalgam.js` 规则合成的单文件版本（未做任何功能改动），便于整体纳入构建。
`quickjs.h` 为原始头文件（JNI 绑定 `../quickjs_jni.c` 使用）。

重新生成方式：clone 上游仓库后按其 `amalgam.js` 的拼接顺序执行（纯文本拼接，
无需 qjs 运行时），或直接编译四个原始 .c 文件。

版本：quickjs-ng master @ 2026-10（含 ES2020+：正则、JSON、Promise、async）。
