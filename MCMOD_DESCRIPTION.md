# WorldGenRender

## 下载文件说明

发布包会提供三个 Jar：

- `worldgenrender-<version>.jar`：运行 Mod 文件，放进客户端或服务端 `mods/` 文件夹。
- `worldgenrender-<version>-api.jar`：开发用 API 文件，供其它 Mod 作为编译依赖，不需要放进普通玩家的 `mods/` 文件夹。
- `worldgenrender-<version>-resources.jar`：资源/文档归档，包含命令说明、MCMod 介绍、API 文档、Javadoc、许可证和更新日志等资料。

这些文件由 GitHub Actions 自动构建：每次推送到 `main` / `master`、提交 Pull Request、手动运行工作流，都会生成三个 artifact。推送到 `main` / `master` 时，工作流会读取 `gradle.properties` 的 `mod_version`，自动发布对应的 GitHub Release，例如 `mod_version=1.0.1` 会发布 `v1.0.1` 下载页。为避免重复发布，同一个版本的 Release 已存在时，工作流只构建并上传 Actions artifact，会跳过 Release 发布步骤；要重新发版需要提高 `mod_version`。

WorldGenRender 是一个面向 Minecraft Forge 1.20.1 的世界生成辅助与渲染工具 Mod，适合整合包作者、地图作者、服务器管理员和世界生成调试场景使用。它可以在游戏内批量预加载、清理、重生成指定区块，并将指定范围导出为俯视图、正交 3D 图片或 OBJ 模型，方便检查地形、结构、群系、水体和地下区域的生成效果。

## 主要功能

- **区块预加载**：按范围加载并生成区块，便于提前检查世界生成结果。
- **区块清理与重生成**：支持清理指定范围或维度内已保存的区块数据，用于重新生成地形。
- **2D 地图渲染**：将区块范围导出为 PNG 俯视图。
- **正交 3D 渲染**：以类似等距视角的方式导出高分辨率 3D 截图。普通 `render3d` 用于展示地表与外观；`render3d cutaway` 和 `render3d box` 用于地下剖面或选区切面。
- **选区渲染**：可在游戏内选定两个方块点，对任意盒状区域进行 3D 渲染。
- **OBJ 导出**：将指定范围导出为 OBJ 模型，方便在外部建模或渲染软件中查看。
- **热重生成辅助**：提供面向服务器调试的在线热重生成流程，并附带可供其它 Mod 调用的 API。

## 基本用法

所有功能通过游戏内命令使用，主命令为：

```mcfunction
/worldgenrender
```

常用示例：

```mcfunction
/worldgenrender preload minecraft:overworld -8 -8 8 8
```

预加载主世界从 `-8,-8` 到 `8,8` 的区块范围。

```mcfunction
/worldgenrender render minecraft:overworld -8 -8 8 8
```

将指定区块范围导出为 2D PNG 地图。

```mcfunction
/worldgenrender render3d minecraft:overworld -8 -8 8 8
```

将指定区块范围导出为正交 3D PNG 图片。普通 `render3d` 主要展示地表和外观，不会主动把区块范围边界外当作空气来制造剖面。

```mcfunction
/worldgenrender render3d cutaway minecraft:overworld -8 -8 8 8
```

导出带地下切面效果的 3D 图片，适合查看洞穴、矿脉和地下结构。Cutaway 会把渲染范围外视为空气，以便暴露边界处的剖切面。

```mcfunction
/worldgenrender render3d quality 1080p
```

在游戏内热切换 3D 渲染单位清晰度。可选 `720p`、`1080p`、`1440p`、`4k`、`8k`。

```mcfunction
/worldgenrender render3d orientation ne
```

设置默认正交 3D 观察方向。可选 `ne`、`nw`、`sw`、`se`。

## 选区 3D 渲染

如果只想渲染一个自定义盒状区域，可以先用选区命令设置两个角点，再执行盒选区渲染：

```mcfunction
/worldgenrender select first
/worldgenrender select second
/worldgenrender render3d box
```

选区渲染适合截取建筑、地形剖面、地下洞穴或小范围生成结果。3D 渲染会按选区大小自动扩展输出图片尺寸，清晰度代表单位范围内的像素密度，而不是固定限制最终图片大小。

## 输出位置

渲染结果默认输出到游戏运行目录下的 WorldGenRender 输出文件夹中，文件名会根据维度、区块范围或选区坐标自动生成。命令中也可以填写自定义文件名，方便整理多次渲染结果。

## 适用场景

- 调试世界生成数据包或自定义维度。
- 检查结构、地形、洞穴、矿脉、水体是否按预期生成。
- 为整合包展示世界生成效果截图。
- 批量预生成区块前进行可视化检查。
- 将 Minecraft 区域导出为 OBJ 模型，用于外部查看或后期处理。

## 注意事项

- 大范围高精度 3D 渲染会占用较多内存和显存，建议先从小范围或 `1080p` 清晰度开始测试。
- `4k`、`8k` 表示单位清晰度更高，最终图片尺寸会随着渲染范围继续增大。
- 清理和重生成区块会影响世界存档数据，正式存档使用前建议备份。
- 服务端使用涉及区块数据读写，建议由管理员执行相关命令。

## 兼容信息

- Minecraft：1.20.1
- Mod Loader：Forge 47.x
- 运行环境：Java 17 或更高版本
