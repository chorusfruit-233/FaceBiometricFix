# FaceBiometricFix 2.2

修复 ColorOS / 一加在应用人脸认证成功后，指纹图标与“继续”按钮重叠的问题。

- 使用单个认证成功 Hook，在当前弹窗中检查两个已知图标与可用确认按钮是否重叠。
- 使用 `INVISIBLE` 保留布局占位，并在确认结束、布局不再重叠或弹窗关闭时恢复图标。
- 缓存视图与反射字段，移除多轮延时扫描和模糊视图匹配。
- 弹窗关闭时移除监听器，热重载前同步清理回调。
- 增加 11 项 Robolectric 回归测试，发布前执行测试、Android Lint 和 Release 构建。

人脸识别失败后的重试沿用 ROM 原生逻辑，本版本未新增自动重扫。

## 安装

要求 Android 15 及以上，以及支持 libxposed API 102 的框架。

在 LSPosed 中启用系统框架和 `com.android.systemui` 两个作用域。首次安装或新增 SystemUI 作用域后重启系统。

附带的 `.sha256` 文件可用于检查 APK 下载完整性。对应 ColorOS / 一加机型的厂商界面兼容性仍需实机验证。

修复思路与已确认的资源 ID 参考 [Zhanfg/FaceBiometricFix 2.2.4](https://github.com/Zhanfg/FaceBiometricFix/pull/1)。
