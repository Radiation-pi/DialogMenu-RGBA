# 介绍

用 YAML 排版，用动作连接业务。

在原版 Minecraft Dialog 中显示独立文字、图片与可点击元素。

 [开始第一份菜单](https://github.com/palkane/DialogMenu-RGBA/wiki/%E7%AC%AC%E4%B8%80%E4%BB%BD%E8%8F%9C%E5%8D%95)[接入 Java API →](https://github.com/palkane/DialogMenu-RGBA/wiki/Java-API)

DialogMenu RGBA 0.1.5 · Java 25 · Minecraft 26.3 · 资源包格式 97.1 起

## 什么是 RGBA 版

这是 DialogMenu 的独立着色器版本。它复用整字字库，在颜色数据中编码文字偏移和图片尺寸，再由核心文字着色器解码，减少按不同纵向位置重复准备字体的开销。

普通玩家不需要客户端 Mod，但必须接受匹配的资源包。点击仍由原版 Dialog 与服务端会话校验处理，不是任意客户端鼠标事件接口。

 YAML 与源图片**→**校验、字库与资源包**→**客户端接收**→**Dialog 与 Shader

## 先读哪一篇

- 首次安装：[运行环境](https://github.com/palkane/DialogMenu-RGBA/wiki/%E5%AE%89%E8%A3%85%E4%B8%8E%E7%89%88%E6%9C%AC%E5%88%87%E6%8D%A2) → [可下载的完整示例](https://github.com/palkane/DialogMenu-RGBA/wiki/%E7%AC%AC%E4%B8%80%E4%BB%BD%E8%8F%9C%E5%8D%95)。
- 想减小资源包：[字库与颜色](https://github.com/palkane/DialogMenu-RGBA/wiki/%E6%96%87%E5%AD%97%E3%80%81%E5%AD%97%E5%BA%93%E4%B8%8E%E6%8D%A2%E8%A1%8C) → [共享图片拉伸](https://github.com/palkane/DialogMenu-RGBA/wiki/%E5%9B%BE%E7%89%87%E4%B8%8E%E5%AE%BD%E9%AB%98%E6%8B%89%E4%BC%B8) → [什么时候需要发新包](https://github.com/palkane/DialogMenu-RGBA/wiki/%E8%B5%84%E6%BA%90%E5%8C%85%E4%B8%8E%E9%87%8D%E8%BD%BD)。
- 接入自己的业务：[外部变量](https://github.com/palkane/DialogMenu-RGBA/wiki/%E5%A4%9A%E9%A1%B5%E3%80%81%E5%8F%98%E9%87%8F%E4%B8%8E%E6%9D%A1%E4%BB%B6) → [集合与表单](https://github.com/palkane/DialogMenu-RGBA/wiki/%E9%9B%86%E5%90%88%E4%B8%8E%E5%8E%9F%E7%94%9F%E8%A1%A8%E5%8D%95) → [完整 Java 示例](https://github.com/palkane/DialogMenu-RGBA/wiki/Java-API)。

> **两个独立项目**原 `palkane/DialogMenu` 保留以前公开的原生版本；本 Wiki 仅对应 `palkane/DialogMenu-RGBA`。两者共用插件名、命令、API 和数据目录，只能安装一个。

## 不包含什么

随包只有 `layout-demo`、`layout-detail` 两个公开示例，没有服务器业务、经济系统或整套菜单。静态图片不是任意 ItemStack 的 3D 模型；没有右键、Shift、中键或服务端滚轮回调。可视化编辑需要另配匹配的编辑客户端，普通玩家显示菜单不需要它。

---

下一篇：[安装与版本切换](https://github.com/palkane/DialogMenu-RGBA/wiki/%E5%AE%89%E8%A3%85%E4%B8%8E%E7%89%88%E6%9C%AC%E5%88%87%E6%8D%A2)
