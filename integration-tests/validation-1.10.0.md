# 1.10.0 验证记录

验证日期：2026-10-10。旧存档由 Folia 1.21.11-14、Worlds 3.12.4、SimpleHomeland 1.9.1 实际创建。目标服务端为 Folia 26.2-7（API 26.2.build.7-beta）、Worlds 4.4.1、Java 25、PlaceholderAPI 2.12.3、XConomy 2.26.3。

| 验证 | 结果 |
|---|---|
| default / flat / void 家园及各自地狱、末地，共 9 个世界迁移 | UUID、钻石块、17 个绿宝石的箱子、命名盔甲架全部保留 |
| 多次重启后按 UUID 加载上述 9 个世界 | 通过，不重复导入 |
| 迁移后原始 `homelands/` 与旧测试服逐文件比较 | 完全一致，备份未改动 |
| H2 旧表初始化及重复启动 | 通过；修复了元数据表名大小写导致的唯一约束误判 |
| MariaDB 10.6.23 旧表通过 MySQL Connector 9.2.0 自动迁移 | 新增列、原家园记录、主世界 UUID 保留；旧维度 UUID 补全 |
| H2、MariaDB 上创建、解锁、重置、删除、重新解锁、全家园删除 | 通过；成功删除后没有活动世界目录或 cleanup 行残留 |
| 重置 prepare 提交后恢复、生成完成但 finish 未提交时恢复 | 通过；后者 UUID 不再次变化 |
| 与旧版相同的公开 API 方法和枚举签名 | javap 对比一致 |
| 与 HuskHomes 相同的反射入口，Location 的 world 为 null | 正确加载卸载的地狱、保留坐标；拒绝未授权访客；回调在玩家 region |
| 相邻 HuskHomes 项目的完整 JAR，真实 BukkitUser.teleportLocally 调用 | 旧保存世界名 + 原 UUID 能传送到卸载的地狱 |
| 铜箱与书架交互、物理库存打开 | 访客容器权限关闭时拒绝访问；测试含风化变种 |
| 先加载其他家园维度，再使用本家园地狱、末地传送门 | 实体均进入本家园目标维度 |
| 单元测试 | 31 项全部通过，覆盖迁移中断、冲突、备份保留、删除后不再导入、维度 UUID 查询/失效和传送门等待 |

测试客户端通过 ViaVersion/ViaBackwards 5.12.1 从 26.1 协议接入 Folia 26.2。测试数据和 MariaDB 实例均在独立临时目录，未连接正式数据库。

尚未实测完整 Velocity/Bungee + Redis 跨服链路，也未启动 Paper 测试服。跨服传送/加载消息已携带目标维度，生产查询方法已在共享数据库测试覆盖。Paper/Folia 26.2 以上的版本需要另行验证，尤其是 Folia 原生异步传送门方法和旧存档迁移路径。

测试同时观察到 XConomy 2.26.3 自身的问题：两个客户端同时退出、位于不同 Folia region 时，`TabListCon.remove_Tab_PlayerList` 中的 `ArrayList.removeIf` 抛出 `ArrayIndexOutOfBoundsException`。家园迁移、API 和容器权限检查均已完成；该异常的堆栈位于 XConomy，当前改动未修改 XConomy。
