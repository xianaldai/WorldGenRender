# WorldGenRender 命令说明

根命令：

```mcfunction
/worldgenrender
```

默认权限等级由配置项 `commandPermissionLevel` 控制。

## 任务管理

```mcfunction
/worldgenrender tasks
/worldgenrender cancel
```

- `tasks`：查看当前 WorldGenRender 后台任务状态。
- `cancel`：取消所有未完成任务。

## 2D 顶视图 PNG 渲染

```mcfunction
/worldgenrender render <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [file]
```

示例：

```mcfunction
/worldgenrender render minecraft:overworld -8 -8 8 8 overworld.png
```

将指定维度、指定 chunk 范围渲染为 2D 顶视图 PNG。输出位于服务端运行目录下的 `worldgenrender/` 目录，除非 `[file]` 指定了其它文件名。

## 3D 正交 PNG 渲染

当前 3D 渲染采用正交捕获/正交画布模型。客户端可用时优先请求执行命令的玩家客户端进行 GPU 正交离屏渲染；客户端不可用时回退到服务端正交 3D 渲染。

### 清晰度与范围关系

`render3dResolution` 是单位范围内清晰度，不是最终图片大小限制：

- `P720`：较低单位密度。
- `P1080`：默认低中档单位密度。
- `P1440`：中高单位密度。
- `P2160` / `4K`：高单位密度。
- `P4320` / `8K`：超高单位密度。

渲染范围越大，最终 PNG 尺寸越大；清晰度越高，每个方块/单位投影使用的像素越多。客户端正交捕获单轴上限为 65535，但仍保留总像素内存保护，避免一次性 PNG 合成导致客户端崩溃。

### 四方向正交取向

3D 正交捕获支持四个水平斜向取向：


| 取向 | 说明                 |
| ---- | -------------------- |
| `ne` | 从 NE 斜向观察选区。 |
| `nw` | 从 NW 斜向观察选区。 |
| `sw` | 从 SW 斜向观察选区。 |
| `se` | 从 SE 斜向观察选区。 |

热命令设置默认精度：

```mcfunction
/worldgenrender render3d quality <p720|720p|p1080|1080p|p1440|1440p|p2160|4k|p4320|8k>
```

示例：

```mcfunction
/worldgenrender render3d quality 8k
/worldgenrender render3d quality p1080
```

该命令会立即修改当前运行时的 `render3dResolution`，后续 3D 渲染立刻使用新单位清晰度；它是热设置，不需要重启游戏/服务器。

设置默认取向：

```mcfunction
/worldgenrender render3d orientation <ne|nw|sw|se>
```

示例：

```mcfunction
/worldgenrender render3d orientation nw
```

默认取向也可在配置项 `render3dOrientation` 中设置。命令中的临时取向不会永久改配置。

### 普通 3D 正交地形 PNG

使用默认取向：

```mcfunction
/worldgenrender render3d <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [file]
```

临时指定取向：

```mcfunction
/worldgenrender render3d orientation <ne|nw|sw|se> <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [file]
```

示例：

```mcfunction
/worldgenrender render3d minecraft:overworld -8 -8 8 8 overworld_3d.png
/worldgenrender render3d orientation se minecraft:overworld -8 -8 8 8 overworld_3d_se.png
```

普通模式主要渲染地表可见地形。客户端 GPU 路径会使用真实客户端世界作为邻居查询视图，让 Minecraft/Forge 原版方块与流体渲染按真实邻居状态进行遮挡裁剪；因此它不会主动把区块范围边界外当成空气，也不应产生地下剖面效果。需要查看地下结构、洞穴、边界切面时使用 `render3d cutaway`。

模式区别：

- `render3d`：地表/外观模式，适合展示自然地形、建筑外观、水面和地表结构。
- `render3d cutaway`：地下剖面模式，会把渲染范围外视为空气以暴露边界切面。
- `render3d box`：玩家两点盒选区模式，也按选区边界做剖切，适合截取建筑局部或地下小范围。

### 3D 地下剖面 / Cutaway PNG

使用默认取向：

```mcfunction
/worldgenrender render3d cutaway <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [file]
```

临时指定取向：

```mcfunction
/worldgenrender render3d cutaway orientation <ne|nw|sw|se> <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [file]
```

示例：

```mcfunction
/worldgenrender render3d cutaway minecraft:overworld -2 -2 2 2 cave_cutaway.png
/worldgenrender render3d cutaway orientation nw minecraft:overworld -2 -2 2 2 cave_cutaway_nw.png
```

Cutaway 模式会渲染范围内地下可见面，并把选区外邻居视为空气，以便暴露边界剖切面。适合观察地下结构、峡谷、洞穴或范围边界截面。

### 选区盒正交 PNG

选区盒由玩家在游戏中手持铁粒右键方块设置：第一次右键设置点 1，第二次右键设置点 2；潜行右键可清除后重选。

使用默认取向：

```mcfunction
/worldgenrender render3d box [file]
```

临时指定取向：

```mcfunction
/worldgenrender render3d box orientation <ne|nw|sw|se> [file]
```

示例：

```mcfunction
/worldgenrender render3d box selected_box.png
/worldgenrender render3d box orientation sw selected_box_sw.png
```

说明：

- `render3d box` 必须由玩家执行，因为选区保存在玩家身上。
- 客户端安装本模组且网络通道可用时，会优先请求客户端 GPU 正交离屏渲染，输出到客户端 `screenshots/worldgenrender/`。
- 客户端不可用时，回退到服务端正交 3D 渲染，输出到服务端运行目录下的 `worldgenrender/`。
- 蓝色选区粒子只用于游戏内预览，不应进入离屏正交输出。
- 选区在 X/Z 上跨越的 chunk 数仍受 `maxRenderChunks` 限制。
- 如果文件名不以 `.png` 结尾，会自动追加 `.png`。

## OBJ 导出

```mcfunction
/worldgenrender exportObj <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [file]
```

示例：

```mcfunction
/worldgenrender exportObj minecraft:overworld -1 -1 1 1 spawn.obj
```

将指定 chunk 范围导出为 OBJ 模型。会尽量保留方块形状盒，适合后续导入建模软件处理。(当前仍然为半成品)

## 世界生成与清理

```mcfunction
/worldgenrender preload <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ>
/worldgenrender clear <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ> [saveAfter]
/worldgenrender clear <dimension> all confirm
/worldgenrender clearDimension <dimension> confirm
/worldgenrender resetSaved <dimension> <fromChunkX> <fromChunkZ> <toChunkX> <toChunkZ>
```

- `preload`：预生成指定 chunk 范围。
- `clear`：热清理指定 chunk 范围的已保存数据。
- `clear all confirm` / `clearDimension confirm`：热清理整个维度。
- `resetSaved`：重置指定范围的已保存标记，使后续可重新生成。

## 常用配置


| 配置项                            | 默认值  | 说明                                                                    |
| --------------------------------- | ------- | ----------------------------------------------------------------------- |
| `commandPermissionLevel`          | `2`     | 执行命令所需权限等级。                                                  |
| `maxRenderChunks`                 | `4096`  | 单次渲染允许的最大 chunk 数。                                           |
| `maxWorldgenChunks`               | `1024`  | 单次预生成/清理允许的最大 chunk 数。                                    |
| `renderPixelsPerBlock`            | `16`    | 2D PNG 每个方块对应的像素边长。                                         |
| `render3dResolution`              | `P1080` | 3D 正交单位清晰度档位，支持`P720`、`P1080`、`P1440`、`P2160`、`P4320`。 |
| `render3dOrientation`             | `NE`    | 3D 正交默认取向，支持`NE`、`NW`、`SW`、`SE`。                           |
| `render3dPixelsPerBlock`          | `32`    | 3D 正交自定义基础像素宽度；作为清晰度下限/兼容参数。                    |
| `render3dVerticalScale`           | `2`     | 旧版服务端 3D 高度缩放兼容参数。                                        |
| `renderUseBlockTextures`          | `true`  | 服务端回退/2D PNG 是否优先尝试读取方块材质。                            |
| `renderTextureFallbackToMapColor` | `true`  | 材质缺失或透明时是否回退地图色。                                        |
| `objExportIncludeInteriorFaces`   | `false` | OBJ 是否导出内部面。                                                    |

## 输出位置

- 服务端任务输出：服务端运行目录下的 `worldgenrender/`。
- 客户端 GPU 正交捕获输出：客户端游戏目录下的 `screenshots/worldgenrender/`。
- `[file]` 可指定文件名；不带 `.png` 或 `.obj` 后缀时会自动补齐对应后缀。
