# 1.10.2 按需迁移、自动卸载与停服保存验证

验证日期：2026-10-10。Java 25、Folia 26.2-7、Worlds 4.4.1、H2、相邻项目带 SimpleHomelandHook 的 HuskHomes 4.10、PlaceholderAPI 2.12.3、XConomy 2.26.3。客户端使用 minecraft-protocol 26.1 经 ViaVersion / ViaBackwards 5.12.1 接入。旧存档由 Folia 1.21.11 / Worlds 3.12.4 真实创建。

修复：主世界和子维度都更新主世界 key 的空闲时间，与整家园卸载检查一致，避免子维度刷新另一个计时条目。构建任务显式跟踪 version 输入，修复仅改版本号时 plugin.yml 被资源缓存保留为旧版本的问题；最终 JAR 内的版本已确认是 1.10.2。

测试覆盖 default / flat / void 的主世界、地狱、末地，共 9 个世界。home / warp 记录存储旧世界名、原 UUID、坐标 (8.5, 82, 8.5) 和朝向 (40, 20)，通过实际 huskhomes:home / huskhomes:warp 命令进入；权限使用独立测试服 OP 和家园管理员附件。

| 场景 | 结果 |
|---|---|
| 旧世界首次通过命令进入 | 9 个维度按需转换，原 UUID、坐标、朝向、钻石块、17 个绿宝石和命名盔甲架保留 |
| 仅加载地狱且玩家停留 65 秒 | 跨过生产检查周期仍保持加载；主世界空闲计时已刷新，没有子维度独立计时条目 |
| 离开后等待自动卸载 | 全部 9 个维度自然保存并卸载，没有探针手动卸载调用 |
| 自动卸载后换用另一条命令进入 | 每个世界的 home 和 warp 都执行过；原内容及首轮新增金块、箱子第 2 格的 3 个钻石全部保留 |
| 第二次离开后自动卸载 | 再次卸载 9 个维度，总计 18 次加载、18 次自动卸载，18 次命令传送全部通过 |
| 重启后使用已有 home / warp | 9 次命令全部通过，修改保留，完成标记时间未变化，没有再次导入旧备份 |
| 另一份旧存档转换、修改后直接正常停服 | 9 个世界全部仍加载，玩家在家园中，卸载事件为 0；探针未调用 World.save 或卸载 |
| 停服时服务端保存 | 日志包含 9 个家园世界的 Saving / Saved world data 和 Saved all worlds，未出现保存异常 |
| 直接停服后的重启加载 | 原 UUID、坐标、朝向、旧内容以及停服前新增方块 / 箱子物品全部通过 |
| 旧目录逐文件比较 | 两个独立测试服的 homelands/ 与旧服务器 diff -qr 均无差异 |
| Gradle build / integrationProbeJar | 通过，37 项单元测试全部通过，JAR 内 plugin.yml 为 1.10.2 |

空闲阈值只在测试服设为 20 秒；生产的 60 秒检查周期保持不变。正常停服时插件结束任务与数据库队列，已加载世界仍在服务端世界列表中，随后由 Folia 停服流程统一保存；Worlds 注册中的 enabled=false 控制启动自动加载，不阻止保存。

成功标记：

```text
PROBE_CLOSED_CYCLE_PASS commands=18 worlds=9 loads=18 unloads=18
PROBE_CLOSED_CYCLE_RESTART_PASS commands=9 worlds=9 loads=9 unloads=0
PROBE_STOP_AFTER_IMPORT_READY worlds=9 loads=9 unloads=0
PROBE_CLOSED_CYCLE_RESTART_PASS commands=9 worlds=9 loads=9 unloads=0
```

原始日志分别保存在独立测试目录的 closed-cycle-first.log / closed-cycle-restart.log，以及 stop-after-import.log / stop-after-import-restart.log。首轮闭环构建的 plugin.yml 曾显示缓存的 1.10.1，修复构建版本输入后，闭环重启和直接停服两轮均使用标记正确的 1.10.2；世界与计时实现一致。

本次覆盖本地 Folia + H2 + 定制 HuskHomes 的完整命令路径。Paper、代理跨服链路及其他未接入家园 API 的传送插件沿用之前记录的验证范围。
