# DialogMenu RGBA

DialogMenu 的独立 RGBA 着色器版本。通过共享整字字库、颜色编码偏移和图片尺寸，减少重复字体与图片资源。

原 [`palkane/DialogMenu`](https://github.com/palkane/DialogMenu) 保留之前公开的原生版本，本项目不向旧仓库发布更新。随包仅有 `layout-demo`、`layout-detail` 两个公开示例，不包含服务器专用菜单、图片或业务配置。

## 中文文档

- [GitHub Wiki](https://github.com/palkane/DialogMenu-RGBA/wiki)：在线中文文档，包含 18 篇正文、分组导航和可下载示例。
- [离线手册](docs/index.html)：下载后直接用浏览器打开，支持搜索、目录、前后篇、复制、示例下载和打印。
- [Wiki 页面源文件](docs/wiki/Home.md)：由离线手册导出，含首页、分组侧栏和 18 篇文档。

内容覆盖安装、完整 YAML 示例、共享字库、图片拉伸、资源包、Java API 和兼容边界。章节组织参考 CraftEngine 中文文档；GitHub Wiki 使用 GitHub 自身样式，离线版保留三栏阅读界面。

## 运行要求

- Java 25。
- Minecraft 26.3 服务端与客户端，服务端需提供相应 Dialog API。
- 客户端接受匹配的服务器资源包；其他核心文字着色器必须兼容。
- 最低资源包格式为 97.1。

Skript、PlaceholderAPI、CraftEngine 均为可选集成，只有配置使用相关功能时才需要安装。普通玩家不需要客户端 Mod；可视化编辑需要单独提供、版本匹配的编辑客户端。

## 构建与安装

在项目根目录执行：

```sh
./gradlew build
```

Windows 使用 `gradlew.bat build`。当前产物为 `build/libs/DialogMenu-rgba-0.1.5.jar`。

1. 备份现有 JAR 与 `plugins/DialogMenu/`，正常停服。
2. 用 RGBA JAR 替换原 DialogMenu JAR，再启动。两个版本共用插件名、命令、API 和数据目录，不能同时安装。
3. 等待资源准备完成，执行 `/dialogmenu status`，确认 `renderer=rgba`。
4. 完成资源包分发并让客户端接收，执行 `/dialogmenu open layout-demo`。

示例支持详情页、返回、消息和关闭。复制 `plugins/DialogMenu/layouts/` 中的示例修改，再执行 `/dialogmenu reload`。

有 CraftEngine 时，插件将生成包登记为其外部资源；仍须检查 CE 生成、上传和客户端接收结果。无 CraftEngine 时，用 `/dialogmenu resources` 导出 `plugins/DialogMenu/resourcepack/`，按服务器自己的流程合包和分发。该命令只导出当前快照，不替代重读 YAML，也不会自动向玩家发包。

## 版本切换与迁移

渲染器由 JAR 内的发行标记固定。旧 `shader-rendering` 配置和 `-Ddialogmenu.rgba` 参数不再选择版本。

切换版本需正常停服、替换 JAR、启动并重新生成和接收匹配资源包。保留 `plugins/DialogMenu/` 中的布局、图片与玩家偏好。RGBA 包状态显示 `renderer=rgba`，对应原生包显示 `renderer=native`。

旧 `backend` 独立点击区域已移除，连空对象也会拒绝加载。升级前将动作、提示和权限迁移到可见的 `text` 或 `image` 元素，不要只删除旧区域而丢失动作。

## 渲染与交互

- 自动准备 3500 个常用汉字，不是完整 Unicode。文字按实际字号共享，不按每个 Y 偏移复制整套汉字。
- 支持 `&`、`§` 颜色、RGB 和粗体；RGBA 文字明确拒绝斜体、下划线、删除线和随机样式。
- 多行文字必须同时设置 `line-width` 和 `max-lines`。字号、玩家倍率、颜色和动态字符仍需提前准备。
- 符合条件的自有图片复用一个共享包装纹理，可独立拉伸宽高。源 PNG 宽高须为 16 的倍数且在 16..256；当前包装路径有效源图最大 240×240，显示尺寸协议为 1..512，仍受画布等限制。
- 原版和 CE 直引纹理保持等比、完整、不旋转、`opacity=255`，不等于自有图片的非等比拉伸路径。
- 文字和图形共享 Z 顺序，点击跟随元素；单/双击按控件隔离，不配置另一套命中区。
- 重载可能重新生成字体 JSON、命中代理和 ZIP，不能承诺每次改偏移都不需要更新资源包。
- 字体和资源在后台准备，失败保留旧快照；过期生成不会覆盖新资源，消费者释放后清理旧 ZIP。

当前实际客户端覆盖 Minecraft 26.3 软件 Vulkan；硬件 OpenGL、Iris、其他核心着色器组合及内存/FPS 尚未验证。开发检查不代替真实客户端、权限或交易验收。

## Java 集合与原生表单

所有 API 调用在服务器主线程执行。通过 Bukkit 服务管理器取得 `DialogMenuApi`，用以下接口绑定已有 YAML 页面：

```java
api.bind(owner, player, page, values,
    selectedId -> { /* 处理配置回调或稳定条目 ID */ },
    closeReason -> { /* 清理本业务会话 */ });
```

Java 绑定可使用 `external: true` 集合，不能使用 Skript 支持的集合。通过 `binding.updateCollection("entries", rows)` 替换集合；每行使用 `DialogMenuEntry`，ID 在集合内唯一且稳定。

行模板可引用 `entry.id`、`entry.name`、`entry.image` 与自定义字段，保留字段不能覆盖。列表追加到原生正文，使用整个正文的滚动，不提供局部滚轮回调、固定页脚或无限虚拟列表；image 字段不会自动生成图片控件。

表单在模板顶层 `forms` 中声明，用 `binding.openForm("create", callback)` 打开。仅提交时回传字段键值，取消与 Esc 不提交并恢复最新列表。输入期间后台刷新不会覆盖表单；关闭、替换、暂停或重载后过期提交失效。字段长度限制不代替业务校验。

完整的配套 YAML 和可编译 Java 类见 Wiki 的“集合与原生表单”和“Java API”。

## 测试与离线构建

`./gradlew build` 包含渲染/交互回归、绑定/表单和实际 RGBA JAR 检查；`./gradlew test` 运行两个可执行行为套件，不依赖 JUnit 或 TestNG。检查不会启动游戏服务器。

离线构建可通过 `DIALOGMENU_LOCAL_CLASSPATH` 提供 26.3 依赖路径，再运行 `python3 build.py` 或 `./gradlew build`。两个构建器均检查真实 RGBA JAR，包括旧开关隔离、资源导出和悬停/点击组件。测试专用原生标记不进入生产包。

Python 构建器将可复现 JAR 与 SHA-256 文件写入 `build/`。`dependencies.lock.json` 的 `--server` 路径仍锁定一套 26.2 依赖，依赖变化时必须显式更新，不能把该锁文件当作 RGBA 支持 26.2 的承诺；依赖源只读。

文档示例检查：

```sh
python3 checks/check-wiki.py --classpath-file <dependencies.txt>
```

先构建 JAR，并设置指向 Java 25 的 `JAVA_HOME`。页面检查需要 Playwright：

```sh
python3 -m http.server 8000 --directory docs
# 在另一个终端运行
node checks/wiki-browser.cjs
```

可通过 `WIKI_URL`、`WIKI_OUT`、`PLAYWRIGHT_MODULE`、`BROWSER_PATH` 指定地址、输出和本机浏览器。

## 维护文档

编辑 `docs/index.html` 后执行：

```sh
python3 scripts/export-wiki.py
```

它会更新 `docs/wiki/` 中的 GitHub Wiki 页面、侧栏、页脚与示例文件。检查是否需要重新导出：

```sh
python3 scripts/export-wiki.py --check
```

Wiki 已初始化，可用 `python3 scripts/publish-wiki.py` 发布这些页面。脚本只推送本项目的 Wiki 仓库，不改旧项目，不强制覆盖历史。

## 发行新版本

1. 修改 `gradle.properties` 的 `version`，Gradle 会同步处理 `plugin.yml`。
2. 执行 `./gradlew clean build`。
3. 提交并推送本 RGBA 项目的 `main`。
4. 向本仓库推送对应版本标签，不向旧项目推送。

标签工作流会校验版本、构建 JAR、检查公开页面和隐私内容，再创建带 RGBA JAR 与完整源码 ZIP 的 GitHub Release。构建或检查失败则停止发行。Wiki、资源、构建脚本和通用编辑桥均包含在源码中；不打包私有菜单或独立编辑客户端 Mod。
