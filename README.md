# maven — 一个原子，两个动作

`maven` 一个原子做两件事，`action` 选其中一个：

| action | 做什么 | 主要出参 |
| --- | --- | --- |
| `build`（默认） | 在工作区里跑 `mvn <goals> <options>`，退出码即结果 | `exitCode` `durationMs` |
| `resolve` | 按 `groupId:artifactId:version[:extension[:classifier]]` 从 Maven 仓库下载构件到工作区 | `resolved` `failed` `snapshots` `bytes` `files` `targets` `urls` `versions` `checksums` `first` `errors` |

`build` 是原来这个原子的全部（[maven-plugin](https://plugins.jenkins.io/maven-plugin/) 的移植）；`resolve` 合并了
`maven-resolve` 1.0.0（10 个入参，repository-connector 2.2.1 的解析侧移植），代码是原样搬过来的 `MavenResolve`，
入参、错误码、消息、出参一个都没改。

## 为什么是一个原子

两个动作的主体都是「这是 Maven」，差别只是跑 `mvn` 还是拉构件。拆成两个仓库的代价是 Maven 的仓库布局、
`maven-metadata.xml`、校验和与凭据要各写一遍，而 `build` 这一步本来就要用同一个 `~/.m2/repository`。
合并之后唯一的反对理由是「参数太多」，而参数多正是该上 `rely` 的信号：

```json
"repoUrl": { "label": "仓库地址（如 https://repo1.maven.org/maven2）", "type": "vuex-input", "default": "",
             "rely": { "operation": "AND", "expression": [ { "key": "action", "value": "resolve" } ] } }
```

所以表单永远只显示「操作 + 这个动作自己的那几个字段」：

| 只在 action=build | 只在 action=resolve |
| --- | --- |
| `goals` `options` `home` | `repoUrl` `coordinates` `username` `password` `localRepository` `targetDirectory` `targetFileName` `checksumPolicy` `timeoutSeconds` |

`action` 与 `failOnError` 无条件显示——两者都读它，只是含义不同：`build` 看 mvn 的退出码，`resolve` 看每个坐标
是否解析成功。出参按动作分（`exitCode`/`durationMs` 对 `resolved`/`files`/…），没有重名，也没有与入参同名——
runner 会把上一步的出参并进下一步的入参，同名等于悄悄改掉下一步的配置。

## resolve：和插件一致的地方

- 一个坐标一个构件，`extension` 默认 `jar`，`classifier` 可为空；URL 布局
  `<repo>/<group 点换斜杠>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<extension>`，
  `MavenResolveIT` 用假仓库的请求日志断言请求路径逐字节相同。
- `targetDirectory` 默认工作区根；`targetFileName` 改副本的名字，可以带子目录；未设置时沿用本地仓库里的文件名。
- `RELEASE` / `LATEST` 通过 `maven-metadata.xml` 的 `<release>` / `<latest>` 解析，取不到时回落到 `<versions>` 列表。
- 快照按时间戳名从远端取（`artifactId-baseVersion-timestamp-buildNumber[-classifier].ext`），本地按
  `-SNAPSHOT` 名存放；版本级 `maven-metadata.xml` 里 `<localCopy>true</localCopy>` 表示远端没有时间戳名。
- `failOnError=false` 时失败的坐标记进 `errors` 继续下一个，和插件 `download()` 里打印
  `Failed to resolve %s - %s` 的行为一致。
- 凭据用 Basic 认证，只发给与原始仓库同 scheme/host/port 的请求；字段留空时取 `x-jenkins.secrets` 绑定的同名凭据。

## resolve：与插件不同的地方（刻意，且被集成测试断言）

1. **不用 Aether，不读缓存**：每次都从仓库取，本地仓库只是落盘的布局。`localRepository` 默认放在工作区
   （`<workspace>/.m2/repository`），要共享 Maven 的缓存就写 `~/.m2/repository`。
2. **校验默认 `fail`**：插件继承 `AetherConstants.DEFAULT_CHECKSUM = "warn"`，而一个「把错字节留在工作区」的
   下载步是静默的错误。需要插件行为时设 `checksumPolicy=warn`：仍然校验、仍然在 `checksums` 里报
   `mismatch`，只是保留字节。
3. **下载后回读**：写入 `.part` 再原子改名，改名前和改名后各算一次 SHA-1 与下载字节比对（40012）；工作区副本
   同样回读。失败不会留下 `.part`，也不会覆盖已有目标。
4. **HTTP 结果要检查**：404 是 40003 并带上 URL；401/403 是 40004（用户侧）；其他非 2xx 是 40008；
   `Content-Length` 与实际字节数不符是 40008 的 `truncated download`，不会变成一个短文件。
5. **重定向自己跟**（最多 5 跳），一旦离开原始 scheme/host/port 就丢掉 `Authorization`。
6. **`targetFileName` 配多个坐标直接拒绝**（40009），不把所有构件改成同一个名字；目标跑出工作区拒绝（40010）。
7. **一个 `repoUrl` 一步**：插件的 `repositoryId` 选择器和全局仓库列表是 job 级配置，这里不做多仓库回退，
   需要回退就多跑一步。

## 错误码

| 码 | 动作 | 含义 | errorType |
| --- | --- | --- | --- |
| 40001 | build | `mvn` 起不来（不在 PATH，`home` 也不是安装目录） | 3 THIRD_PART |
| 40014 | 两者 | `action` 不认识，消息里列出 `build / resolve` | 1 USER |
| 40001 | resolve | 入参不合法（坐标语法、用户名密码不配对、checksumPolicy 未知） | 1 USER |
| 40002 | resolve | `repoUrl` 缺失或不是 http(s) | 1 USER |
| 40003 | resolve | 构件不存在（HTTP 404），消息里带 URL | 2 THIRD_PART |
| 40004 | resolve | 仓库拒绝凭据（HTTP 401/403） | 1 USER |
| 40005 | resolve | 仓库没有发布任何校验和，且策略为 fail | 2 THIRD_PART |
| 40006 | resolve | 校验和与字节不符，且策略为 fail | 2 THIRD_PART |
| 40007 | resolve | `maven-metadata.xml` 缺失/无法解析/没有可用版本 | 2 THIRD_PART |
| 40008 | resolve | 其他 HTTP 错误、传输失败、短读、重定向异常 | 2 THIRD_PART |
| 40009 | resolve | `targetFileName` 与多个坐标冲突 | 1 USER |
| 40010 | resolve | 目标路径跑出工作区 | 1 USER |
| 40012 | resolve | 落盘后回读的 SHA-1 与下载字节不符 | 3 PLUGIN |
| 40013 | resolve | 请求超时 | 2 THIRD_PART |

## 验证

```bash
mvn verify          # → dist/maven/{maven.jar,task.json}; 25 个集成测试（failsafe）
```

- `MavenIT`（5）用一个假的 `mvn` 放上 PATH：参数到达工具、工具跑在工作区、退出码是结果、`failOnError` 决定
  步骤成败、未知 `home` 回落到 PATH，以及**不传 `action` 时就是 build**（旧用例一条都没有带 `action`）。
- `MavenResolveIT`（20）是 `maven-resolve` 的整套用例原样搬过来：`tools/fake_maven_repo.py` 把一棵 Maven 2 布局
  的目录树用真 HTTP 提供出去，并记录每个请求的路径 / `Authorization` / `User-Agent`；断言用的期望值（路径、字节、
  SHA-1、Basic 头）都在测试里自己算，不读原子的输出。用例含：布局路径、classifier/extension、`targetFileName`
  （含子目录与越界）、`fail`/`warn`/`ignore` 三种校验策略、sha256 回退、404/401/500/不可达、超时、短读、
  `RELEASE`/`LATEST`、两种快照、坏 metadata、坏坐标、`failOnError=false`。

移植时只改了两处，都是合并本身带来的：每条用例显式带上 `action=resolve`（它不再是原子唯一做的事），
以及请求头 `User-Agent` 从 `jenkins-atom-maven-resolve` 改成 `jenkins-atom-maven`（旧名字已经不存在了）。
其余断言逐字未动，一条没有跳过。

## 边界

- `build` 只调 `mvn`，不碰 `resolve` 的入参；`resolve` 只下载，不跑 `mvn`。要「先解析构件再构建」就两步。
- `resolve` 的 `localRepository` 默认在工作区里：它对 Maven 自己的缓存无感，`build` 也不会读它。
- `checksumPolicy` 默认 `fail`，比插件严格；想复现插件行为写 `warn`。
