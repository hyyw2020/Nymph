// SPDX-License-Identifier: MIT
package dev.nymph.auto.legacy.screenshot

/**
 * 兼容降级路径标记（v3.0.0 / 迁移期 ADR-068）。
 *
 * 打上此注解的类/函数表示「截图取证链的历史债务」，不是新设计，计划随
 * 真值录制链成熟后移除（forRemoval）。所有调用点必须显式标注本注解，
 * 让「降级保留」在源码层可见、可审计、可被门禁扫描——避免它变成永远
 * 删不掉的暗债。
 *
 * ## 为什么是注解而不是注释
 *
 * 注释会被重构遗忘；注解可被 `verify_release.py` 的静态门禁检查：
 * - `legacy/screenshot/` 外任何文件真实调用截图 API → 红；
 * - 调用 `CompatFallback` 的站点未标注 `@FallbackPath` → 红。
 *
 * @param since 引入兼容标记的语义版本（非 APP versionName）。
 * @param forRemoval 是否计划在后续主版本移除。
 * @param reason 降级原因，供审计和 fail-loud 提示复用。
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.SOURCE)
annotation class FallbackPath(
    val since: String = "v3.0.0",
    val forRemoval: Boolean = true,
    val reason: String = "截图取证链兼容降级，新链禁止引用",
)
