# 字幕查词（SubWord）

在 App 里看 YouTube（手机版网页），点字幕里的词：视频暂停，弹出 AI 给的这个词在这句里的意思（音标、意思、原形、其他意思），可以加入生词本；点别处收起，视频接着播放。不会一下显示整句翻译，要的时候点「翻译整句」。

- 简介、评论等网页文字：长按选中，菜单里选「AI 查词」。选中的超过 4 个词时直接翻译。
- 生词本：点一条回到视频里查这个词的那一秒；长按删除；右上角「导出」。
- 在 YouTube App 里「分享」→「字幕查词」，可以直接在这里打开那个视频。
- 卡片底部显示「第 N 次查这个词」：查得多的词，说明还没记住。

## 用法

1. 右上角「设置」：填 API Key。默认用 DeepSeek 的 Anthropic 接口（和 iPad 上的 LearningPack 一样），也可以选 OpenAI 兼容接口。点「保存并测试」检查一下。
2. 打开视频，在播放器里打开字幕（CC）。自动生成的英文字幕也可以点。
3. 点字幕里的词。

## 生词导入 iPad

生词本导出的 `下载/SubWord/vocab-*.jsonl`，字段和 LearningPack 的 `PackFormat/import_vocab.sh` 一致：

```sh
adb pull /sdcard/Download/SubWord/vocab-20261014-211335.jsonl /tmp/
cd ~/vscode/Learning && PackFormat/import_vocab.sh /tmp/vocab-20261014-211335.jsonl
```

## 构建与安装

```sh
sh ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

小米手机要先在「开发者选项」里打开「USB 安装」，安装时在手机上确认。

## 代码

纯 Java、不用 AndroidX，和 time-tracker-android 一样的构建配置（AGP 8.13.2、JDK 17）。

| 文件 | 内容 |
|---|---|
| `assets/subword.js` | 注入网页：把 `.ytp-caption-segment` 里的词包成可以点的 span；在 window 捕获阶段截下对词的点击，播放器收不到；暂停、高亮、把词和字幕交给 App；`__subwordResume()` 接着播放；`__subwordSelection()` 取长按选中的文字 |
| `MainActivity` | WebView、释义卡片（放在词的上方或下方）、全屏播放、长按菜单、返回键 |
| `Ai` | 提示词（改自 LearningPack 的 `VocabAI`）、流式请求、解析「音标 / 意思 / 原形 / 其他」 |
| `VocabDb` | SQLite：生词本 `vocab`（同一个词只记一条）、查词记录 `lookups`；导出 jsonl |
| `VocabActivity`、`SettingsActivity` | 生词本、AI 设置 |

调试版开了 WebView 远程调试：`adb forward tcp:9229 localabstract:webview_devtools_remote_<pid>` 后用 Chrome 的 `chrome://inspect` 或调试协议查看网页。
