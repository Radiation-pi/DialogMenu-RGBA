# DialogMenu RGBA

Independent RGBA Shader edition of DialogMenu. The original `palkane/DialogMenu`
repository retains its previously published native version; this project does
not update or publish releases to that repository. The plugin
contains only the `layout-demo` and `layout-detail` examples; it does not ship
server-specific menus, images, or configuration.

## 中文 Wiki

打开 [`docs/index.html`](docs/index.html) 阅读中文版 Wiki，可下载后直接用浏览器离线打开。
内容包括安装、完整 YAML 示例、共享字库、图片拉伸、资源包、Java API 和兼容边界；
提供搜索、页内目录、示例复制/下载和手机目录。页面组织参考 CraftEngine 中文文档，
不依赖外部脚本或字体。

Wiki 示例校验（先构建 JAR，使用 Java 25 与 26.3 依赖）：
`python3 checks/check-wiki.py --classpath-file <dependencies.txt>`。
页面交互校验：安装 Playwright，启动 `python3 -m http.server 8000 --directory docs`，
另一个终端执行 `node checks/wiki-browser.cjs`。可用 `WIKI_URL`、`WIKI_OUT`、
`PLAYWRIGHT_MODULE` 和 `BROWSER_PATH` 指定地址、输出和本机浏览器。

## Requirements

- Java 25
- Minecraft 26.3 server and client; custom core text shaders must be compatible
- A client that supports Dialog and accepts the server resource pack
- Build: `./gradlew build`; output: `build/libs/DialogMenu-rgba-<version>.jar`
- Source is under `src/main/java/dev/dialogmenu/`; only the two public test
  layouts are included under `src/main/resources/layouts/`.

Skript, PlaceholderAPI, and CraftEngine are optional integrations. Use the
corresponding plugin only when your configuration relies on that integration.

## Install

1. Stop the server and replace any existing DialogMenu JAR with
   `DialogMenu-rgba-<version>.jar`, then start the server. Do not install both:
   they have the same plugin identity, commands, API, and data folder.
2. Accept the server resource pack.
3. Run `/dialogmenu status` (expect `renderer=rgba`), then `/dialogmenu open layout-demo`.

The bundled demo opens `layout-detail`; its buttons demonstrate back, message,
and close actions. Copy the two YAML files from
`plugins/DialogMenu/layouts/` to make your own layouts, then run
`/dialogmenu reload`.

With CraftEngine installed, DialogMenu registers its generated resource pack
with CraftEngine. Without CraftEngine, run `/dialogmenu resources` and publish
the generated `plugins/DialogMenu/resourcepack/` through your server's normal
resource-pack delivery.

This edition shares whole-character fonts, includes 3500 common Han characters,
and encodes offsets and image sizes for the shader. Its actual client coverage is
26.3 software Vulkan; hardware OpenGL, Iris, and other core shader combinations
are not yet verified.

The renderer is fixed by the installed edition. Old `shader-rendering` settings
and `-Ddialogmenu.rgba` flags do not switch editions. To switch, stop the server,
replace the old JAR with the other edition (never leave both), restart, rebuild
and deliver the matching resource pack. Keep your existing `plugins/DialogMenu`
data and player preferences. `/dialogmenu status` reports `renderer=native` or
`renderer=rgba` for this project. Older layouts with separate `backend` regions must first migrate
their actions to text/image elements before upgrading to either edition.

## Source

The complete Java source, resources, Gradle build, and wrapper are in
this repository. The build resolves Paper and Skript compile-time APIs from
their public Maven repositories. CraftEngine integration is optional and
loaded reflectively, so CraftEngine is not required to build. Only the two
public test layouts are included. The server editor bridge and shared protocol
are included; an editor client Mod is separate. No server-specific files are included.

## Publishing a new version

1. Update `version` in `gradle.properties`; `plugin.yml` receives it during
   the Gradle resource-processing step.
2. Build and verify with `./gradlew clean build`.
3. Commit the updated source and push it to `main`.
4. Push a matching version tag to this RGBA repository, not the original project.

The tag workflow checks the version, builds the JAR from the committed source,
packages the public source, and creates a GitHub Release with the RGBA JAR
and source archive. A
failed build or privacy/layout check prevents the release.

## Build and test status

The build runs offline layout, rendering, resource, binding, form, and click-token
checks. These do not replace testing on your server and client.

## Interaction and rendering fixes

The original rendering and interaction fixes are retained, targeting Minecraft
26.3 / Java 25 with minimum resource-pack format 97.1. Text uses Adventure's
legacy parser for both `&` and `§` formatting, including RGB colors. Measurement,
wrapping, and output share resolved glyph styles. Underline, italic,
strikethrough, and obfuscated shader text are explicitly rejected. Wrapped text
still requires `line-width` and `max-lines`. Reloads may regenerate the resource
pack; offset encoding is not a guarantee that every edit needs no new pack.

Control IDs isolate double-click targets, graphics and text share z ordering, generated ZIPs are pruned once snapshots and CraftEngine consumers release them, and ordinary placeholders survive PAPI fallback. Explicit exit actions clean up Escape-enabled sessions. CE publication no longer waits on the server thread, and stale snapshot generations cannot replace newer ones.

`./gradlew build` and `./gradlew test` run both executable test suites: the PR's rendering/interaction regressions and the binding/form behavior tests. These checks do not replace real client or Skript/PAPI/CraftEngine integration testing. CraftEngine remains optional and is accessed reflectively.

For an offline build, supply a 26.3 dependency classpath through
`DIALOGMENU_LOCAL_CLASSPATH` and run `python3 build.py` or `./gradlew build`.
Both builders test the actual RGBA JAR, including old-config isolation,
resource exports, and hover/click components. The test-only native marker
exercises shared compatibility paths; it is never included in the production
JAR. The Python script writes a reproducible RGBA JAR and SHA-256 file to `build/`.
The contributor's `dependencies.lock.json` records an exact
26.2 set for `--server /path/to/server`; update that lock explicitly when
dependencies change. Dependency sources are read only.

## Plugin-owned collections and native forms

All API calls run on the server thread. Get `DialogMenuApi` from Bukkit's
services manager and bind a YAML page with `api.bind(owner, player, page,
values, selectedId -> ..., closeReason -> ...)`. Plugin bindings may use
external collections, but not Skript-backed collections.

```yaml
schema-version: 1
id: catalog
collections:
  entries: {external: true}
forms:
  create:
    title: Create
    submit: Submit
    cancel: Cancel
    fields:
      name: {label: Name, max-length: 24}
pages:
  catalog-list:
    canvas: {width: 300, height: 180}
    frontend:
      heading: {type: text, content: Catalog}
    flow-lists:
      content:
        collection: entries
        row: '{entry.name} · {entry.size}'
        empty: No entries
```

Call `binding.updateCollection("entries", rows)` with any number of
`new DialogMenuEntry(stableId, name, image, Map.of("size", "10x20"))` entries.
IDs must be unique within the collection and use letters, digits, `:`, `_`,
or `-`. Row clicks return the stable ID through the binding's selection
callback. Replacing the collection displays newly added entries without
changing YAML. Reserved `entry.id`, `entry.name`, and `entry.image` cannot be
overridden by custom fields.

Rows join the native Dialog body and use whole-body scrolling. There are no
fixed slots, custom wheel listeners, fixed headers/footers, or server-side
scroll offsets. The image token is display data, not an automatically rendered
image widget.

Call `binding.openForm("create", fields -> ...)` to receive submitted text by
field key. Cancel and Escape restore the latest list without invoking the
submission callback. Refreshes do not overwrite an open form, and stale or
repeated submissions are ignored after close, replacement, suspension, or
reload. Missing fields and values longer than the declared limit are rejected;
the calling plugin must still validate business rules on the server.
