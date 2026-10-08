# Block Maker

Minecraft 1.21.11 Fabric 客户端方块数据调试器，与 [SEE](https://github.com/sinkie114/see)（实体）和 [NBT Maker](https://github.com/sinkie114/nbt-maker)（物品）配套。对准方块按 **F7** 打开，编辑方块状态与方块实体数据，单人 / 局域网房主点击“同步”写回世界，多人严格只读。使用方法见 [使用说明](使用说明.md)。

1.0.0 首个版本：
- 方块状态属性表单：布尔属性单击切换，其余从可选值列表选择；“更换方块”可搜索所有原版与 mod 方块，同名属性尽量保留。
- 方块实体数据（箱子物品、告示牌文字、刷怪笼等）以 SNBT 查看和编辑；可编辑副本直接读取集成服务器数据，不会因为客户端没有箱子内容而误删物品。
- 撤销 / 重做（Ctrl+Z、Ctrl+Y），SNBT 复制、粘贴、整体编辑和导出文件，“邻近更新”开关。
- 每 Tick 对比世界中的真实方块，外部变化会提醒；同步后读回实际结果，并列出被游戏调整或忽略的字段。
- 更换容器方块时先清空旧容器，物品不会掉落到地上。

开发构建：`build-local.bat --offline build` 或 `./gradlew build`（JDK 21 或更高）。运行独立测试世界：`./gradlew runClientGameTest`。测试代码位于 `src/gametest`，不会打入发布 jar。

许可证：CC0，见 LICENSE。
