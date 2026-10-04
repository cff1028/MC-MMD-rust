# 第三方库声明

本项目使用以下开源库。编译并分发二进制文件的用户应包含相应的许可证声明。

---

## 直接依赖

### Lumen 空间菜单

| 库名 | 版本/来源 | 许可证 | 用途 |
|------|-----------|--------|------|
| LWJGL NanoVG bindings | 3.3.3，与 Minecraft 1.21.4 的 LWJGL ABI 一致 | BSD-3-Clause | NanoVG Java API 与各平台原生库 |
| NanoVG | LWJGL 3.3.3 内置源码 | zlib | 抗锯齿矢量界面和文本 |
| FontStash | LWJGL 3.3.3 内置源码 | zlib | NanoVG 字体图集 |
| stb_truetype | LWJGL 3.3.3 内置源码 | MIT | 字形栅格化 |
| Bjoern Hoehrmann UTF-8 decoder | FontStash 内置源码 | MIT | 字符串解码 |

完整许可位于 `common/src/main/resources/META-INF/licenses/mmdskin/`，随 Fabric/NeoForge 发布 JAR 一同分发。
来源为 [LWJGL 3.3.3](https://github.com/LWJGL/lwjgl3/tree/3.3.3) 及 [UTF-8 decoder](https://bjoern.hoehrmann.de/utf-8/decoder/dfa/)。
NanoVG 原生资源覆盖 Windows、Linux、macOS 的上游支持架构；开发与测试运行自动选择当前操作系统/CPU 的原生库。
该覆盖仅描述 NanoVG 资源，不扩展 MMD Rust 引擎或 Vivecraft 自身支持的平台。
系统字体从操作系统读取，不打包或分发 Microsoft YaHei 等系统字体。


### Rust 引擎 (rust_engine)

| 库名 | 版本 | 许可证 | 仓库地址 |
|------|------|--------|----------|
| rapier3d | 0.32.0 | Apache-2.0 | https://github.com/dimforge/rapier |
| glam | 0.31.0 | MIT 或 Apache-2.0 | https://github.com/bitshifter/glam-rs |
| mmd (mmd-rs) | 0.0.6 | BSD-2-Clause | https://github.com/aankor/mmd-rs |
| jni | 0.21 | MIT 或 Apache-2.0 | https://github.com/jni-rs/jni-rs |
| nalgebra | 0.34 | Apache-2.0 | https://github.com/dimforge/nalgebra |
| rayon | 1.11.0 | MIT 或 Apache-2.0 | https://github.com/rayon-rs/rayon |
| thiserror | 2.0 | MIT 或 Apache-2.0 | https://github.com/dtolnay/thiserror |
| bitflags | 2.6 | MIT 或 Apache-2.0 | https://github.com/bitflags/bitflags |
| byteorder | 1.5 | Unlicense 或 MIT | https://github.com/BurntSushi/byteorder |
| encoding_rs | 0.8 | MIT 或 Apache-2.0 | https://github.com/hsivonen/encoding_rs |
| log | 0.4 | MIT 或 Apache-2.0 | https://github.com/rust-lang/log |
| once_cell | 1.19 | MIT 或 Apache-2.0 | https://github.com/matklad/once_cell |
| image | 0.25 | MIT 或 Apache-2.0 | https://github.com/image-rs/image |
| vek | 0.17 | MIT | https://github.com/yoanlecoq/vek |

### 传递依赖（通过 rapier3d）

| 库名 | 许可证 | 仓库地址 |
|------|--------|----------|
| parry3d | Apache-2.0 | https://github.com/dimforge/parry |
| simba | Apache-2.0 | https://github.com/dimforge/simba |

---

## 设计参考

以下项目作为架构设计参考，其代码未被包含在本项目中。

| 项目 | 许可证 | 仓库地址 | 参考内容 |
|------|--------|----------|----------|
| KAIMyEntity | MIT | https://github.com/kjkjkAIStudio/KAIMyEntity | 原始 Minecraft MMD 模组 |
| KAIMyEntity-C | MIT | https://github.com/Gengorou-C/KAIMyEntity-C | 本项目直接前身（二次开发基础） |
| Saba | MIT | https://github.com/benikabocha/saba | 物理系统设计 |
| nphysics | Apache-2.0 | https://github.com/dimforge/nphysics | 骨骼层次结构 |
| mdanceio | MIT | https://github.com/ReaNAiveD/mdanceio | 动画系统 |
