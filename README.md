# LinkDisk

LinkDisk 是一款原生 Kotlin Android WebDAV 文件共享应用，可通过局域网或官方 FRP 隧道分享手机中的文档、照片和下载内容。

**当前版本：1.0** · **最低系统：Android 7.0（API 24）**

## 下载

[从 GitHub Releases 下载 LinkDisk 1.0 APK](https://github.com/Lensi-zhang/LinkDisk/releases/download/v1.0/LinkDisk-1.0.apk)

SHA-256：`F1D952F3D50C1C74D5F88CB44873026A18067075A6DF077D8ABBB6DF90EE5629`

安装前请核对以上校验值。Android 可能需要在系统设置中允许当前浏览器或文件管理器安装未知来源应用。

## 功能

- 使用系统存储访问框架（SAF）选择共享目录，无需存储权限。
- 提供 WebDAV `OPTIONS`、`PROPFIND`、`GET`、`HEAD`、`PUT`、`DELETE`、`MKCOL`、`COPY`、`MOVE`、`LOCK` 和 `UNLOCK` 支持。
- 默认启用用户名和密码认证，公网访问要求至少 16 位密码。
- 支持粘贴或导入官方 `frpc` TOML 配置及旧版 INI 配置。
- 自动识别 HTTP、HTTPS、TCP 隧道、本地端口和公网地址。
- 单候选自动应用，多候选由用户显式选择，并同步 WebDAV 端口。
- 提供“测试并启动公网”，启动后通过带认证的 `OPTIONS`/`DAV` 探测确认可用性。
- 原始 FRP 配置保持不变并加密保存，运行时交由官方 `libfrpc.so -c` 执行。
- 前台服务、失败退避重连及停止后的进程清理。

## 使用教程

### 1. 安装与首次准备

1. 从本页“下载”部分获取 APK，核对 SHA-256 后安装。若系统阻止安装，请按提示允许当前浏览器或文件管理器安装未知来源应用。
2. 打开 LinkDisk，点击“选择目录”，在系统文件选择器中授权要共享的文件夹。
3. 请只选择需要共享的目录。连接成功后，客户端可以读取、上传、修改和删除该目录中的内容。

LinkDisk 使用系统存储访问框架（SAF），不需要 Root，也不会申请整个手机存储的访问权限。

### 2. 启动局域网共享

1. 让手机和电脑连接同一个路由器或局域网。
2. 在“连接设置”中检查端口、用户名和密码。通常可保留默认端口；端口被占用时，可改为 `1024–65535` 范围内的其他端口。
3. 默认用户名为 `admin`，首次运行会生成随机密码。建议在可信局域网中也保留身份验证，不要把密码粘贴到公开文档或截图中。
4. 点击“启动 WebDAV 服务”，等待状态变为“局域网共享中”。
5. 点击“复制地址”，获取类似 `http://192.168.1.100:8080` 的地址。这里的 IP 和端口仅作示例，请以 App 实际显示为准。

### 3. 在电脑上连接

#### Windows

1. 打开“此电脑”，选择“映射网络驱动器”。
2. 选择一个未使用的驱动器号，在“文件夹”中粘贴 App 显示的 WebDAV 地址。
3. 需要时勾选“使用其他凭据连接”，然后输入 LinkDisk 中设置的用户名和密码。
4. 连接成功后，即可像普通磁盘一样查看和复制文件。

Windows 版本及系统安全策略可能影响内置 WebDAV 客户端。若无法映射，可改用支持 WebDAV 的文件管理器；不建议为了连接而降低系统安全设置。

#### macOS

1. 打开“访达”，选择“前往”→“连接服务器”，或按 `Command + K`。
2. 输入 App 显示的完整地址并点击“连接”。
3. 选择注册用户，输入 LinkDisk 的用户名和密码。

#### 其他 WebDAV 客户端

在客户端中新建 WebDAV 连接，将 App 显示的完整地址填为服务器地址，并填写相同的用户名和密码。不要只填写 IP 而遗漏 `http://`、`https://` 或端口。

### 4. 上传、下载与管理文件

- **下载：** 直接从已连接的 WebDAV 目录复制文件到电脑。
- **上传：** 将电脑中的文件或文件夹复制到 WebDAV 目录，文件会写入手机已授权的共享目录。
- **重命名、移动和删除：** 可在客户端中直接操作。这些操作会实际修改手机中的文件，删除前请确认不再需要。
- **传输期间：** 保持 WebDAV 服务运行和网络连接稳定。若系统限制后台活动导致传输中断，请在系统设置中允许 LinkDisk 后台运行，传输完成后再恢复原设置。

### 5. 开启公网共享（可选）

公网共享需要额外准备可用的 `frps` 服务和兼容的官方 `frpc` 配置。仅在家或公司局域网使用时，无需进行本节操作。

1. 从 `frps` 服务提供方获取官方 `frpc` TOML 配置，或带 `[common]` 的旧版 INI 配置。LinkDisk 不会代替你创建 `frps` 服务。
2. 在 App 的公网配置区域点击“粘贴配置”，或点击“导入文件”选择配置文件。文件必须使用 UTF-8 编码，且不超过 256 KiB。
3. 只有一个可用隧道时，App 会自动应用；识别出多个隧道时，点击“选择隧道”，选择用于 WebDAV 的那一项。
4. 检查识别结果：FRP 配置的 `localPort` 必须与“连接设置”中的 WebDAV 端口一致，公网地址应与 `frps` 实际提供的访问地址一致。
5. 设置非空用户名和至少 16 位密码。公网建议使用 HTTPS；HTTP 不会加密文件内容或 Basic 认证凭据。
6. 点击“测试并启动公网”，等待界面显示“公网 WebDAV 已通过认证探测”。
7. 点击“复制公网地址”，在电脑或其他设备的 WebDAV 客户端中使用该地址和 LinkDisk 的用户名、密码连接。

高级用户可展开“高级配置”修改原始配置和公网 URL。若测试失败，请依次检查 `frps` 是否在线、配置是否完整、隧道端口是否正确、手机网络是否可用，以及公网地址是否与隧道匹配。

### 6. 停止共享

使用完毕后返回 LinkDisk，点击“停止服务”。不要只关闭界面或清理后台。停止服务后，局域网 WebDAV 和由 LinkDisk 启动的 FRP 连接都会停止，之前挂载的客户端将无法继续访问。

### 7. 常见问题

**电脑无法打开地址**

- 确认 App 显示“局域网共享中”或“公网 WebDAV 已通过认证探测”。
- 局域网连接时，确认两端处于同一网络，并检查路由器是否开启了 AP/客户端隔离。
- 使用 App 当前显示的完整地址，不要沿用更换网络前的旧 IP。

**提示用户名或密码错误**

请输入 LinkDisk“连接设置”中的用户名和密码，而不是手机锁屏密码、GitHub 密码或 FRP Token。修改认证信息后，需要在客户端中同步更新保存的凭据。

**公网测试失败**

局域网共享与公网隧道相互独立。公网失败时仍可先使用局域网共享；随后检查 `frps` 服务、FRP 配置、端口和公网 URL。不要把完整 Token 或配置文件发到公开 Issue。

**传输中断或服务被系统结束**

保持手机网络稳定，避免在传输过程中停止服务，并检查系统的省电策略和后台运行限制。

## 安全说明

- 公网访问必须设置非空用户名和至少 16 位密码。
- **HTTP 不加密传输内容或 Basic 认证凭据，公网使用时应优先选择 HTTPS。** 仅在可信网络中使用 HTTP。
- WebDAV 密码和 FRP 原始配置使用 Android Keystore 保护的 AES-GCM 加密保存。
- 配置识别错误、应用状态和日志不应包含 Token、密码或整段配置。
- 分享目录即授予客户端对该目录内容的读写能力；请仅选择需要分享的目录，并在使用后停止服务。
- 从其他来源安装 APK 前，请验证签名和哈希，避免安装被篡改的包。

## 从源码构建

环境要求：JDK 17、Android SDK 34。

```powershell
# 单元测试与 lint
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain

# 安装调试版本到已连接设备
.\gradlew.bat :app:installDebug --console=plain

# 构建发布版本
.\gradlew.bat :app:assembleRelease --console=plain
```

未配置签名时，`assembleRelease` 生成未签名 APK。正式构建可在本机被 Git 忽略的 `local.properties` 中配置：

```properties
linkdiskStoreFile=C:/path/to/private-release.jks
linkdiskStorePassword=replace-with-local-secret
linkdiskKeyAlias=linkdisk
linkdiskKeyPassword=replace-with-local-secret
```

签名密钥和密码不得提交到仓库。丢失发布密钥将导致无法以同一签名发布后续升级。

## 项目结构

- `MainActivity.kt`：配置向导、状态展示及用户交互。
- `WebDavServer.kt` / `WebDavService.kt`：WebDAV 服务、认证、公网探测及生命周期。
- `FrpcConfigInspector.kt`：只读 TOML/INI 隧道识别。
- `FrpcProcess.kt` / `TunnelConfig.kt`：原始配置校验、脱敏及官方原生进程管理。
- `SecretStore.kt`：基于 Android Keystore 的本地加密存储。

## 第三方组件

- AndroidX、Material Components
- Kotlin
- NanoHTTPD
- 官方 FRP 原生客户端

各组件遵循其原有许可证。LinkDisk 与 CHMLFRP 或 FRP 官方没有隶属或背书关系。
