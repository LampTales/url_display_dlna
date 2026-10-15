# GitHub APK 发布

仓库中的 `.github/workflows/android.yml` 会在普通推送时执行测试并上传构建产物，在推送形如 `v0.5.0` 的标签时创建 GitHub Release 并附加 APK。

## 发布步骤

```bash
git add .
git commit -m "Release 0.5.0"
git tag v0.5.0
git push origin HEAD --tags
```

Actions 完成后，在 GitHub 的 **Releases** 页面下载对应 APK 和 `.sha256` 校验文件。版本号应同时更新 `app/build.gradle` 中的 `versionCode` 和 `versionName`；`versionCode` 必须递增。

## 调试 APK 与正式 APK

当前工作流生成的是 `assembleDebug` 的调试签名 APK，适合个人测试和早期开源预览。它不是适合长期分发的正式签名包。

如果要公开稳定版本，应：

1. 生成自己的 Android signing keystore；
2. 将 keystore 内容和密码保存为 GitHub Actions Secrets，不要提交到仓库；
3. 增加 release signing 配置并构建 `assembleRelease`；
4. 保管好签名密钥，因为同一应用后续升级必须使用同一签名。

本地编译后手工上传 APK 也可行，但每次容易漏记版本、测试和校验值。Actions 让构建环境和产物可复现，更适合作为默认发布方式；本地构建适合在发布前排查问题或没有 GitHub Actions 时临时使用。
