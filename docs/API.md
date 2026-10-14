# 录像机 App API：HTTPS、用户权限与自动续期

App 服务固定为 **9079**，网页固定为 **8079**，两者均只接受 HTTPS。电脑用户管理负责新建账号和分配摄像头，权限覆盖实时、录像查询、回放和下载；新用户和新摄像头都不会自动授予权限。

## 验证加密连接

基础地址是 `https://录像机电脑IP:9079`。所有请求，包括登录密码、JSON 元数据、JPEG/MJPEG、MP4 和原始 MKV，都在 TLS 1.2/1.3 中传输。HTTP 地址不提供登录或媒体服务。

局域网使用电脑“加密连接”导出的公钥 CA 证书，客户端可将它加入专用信任库并正常校验服务器证书与地址。安卓 App 也支持核对电脑显示的 SHA-256 **公钥指纹**后固定此录像机：指纹是 `SHA256(leafCertificate.getPublicKey().getEncoded())` 的 64 位十六进制形式，不是整张证书的摘要。固定公钥时同时检查证书有效期，并仅应用于已配对的主机、端口，不信任其他主机或所有证书。

电脑导出的手机连接文件不含密码，格式为：

```json
{"version":1,"apiUrl":"https://RECORDER_HOST:9079","publicKeySha256":"64_HEX_CHARACTERS_FROM_THE_DESKTOP","name":"录像机"}
```

公信 HTTPS 域名使用系统证书与主机名验证。不要实现信任所有证书、关闭主机名验证或遇错降级 HTTP。首次信任与证书安装步骤见 [使用说明](使用说明.md#https-配对)。

## 用户登录与长期记忆

`POST /api/v1/auth/login`，`Content-Type: application/json`：

```json
{"username":"viewer","password":"YOUR_PASSWORD"}
```

成功返回：

```json
{
  "accessToken":"ACCESS_TOKEN",
  "token":"ACCESS_TOKEN",
  "refreshToken":"ROTATING_REFRESH_TOKEN",
  "tokenType":"Bearer",
  "username":"viewer",
  "expiresAt":"2026-10-12T04:15:00Z",
  "refreshExpiresAt":"2026-11-11T04:00:00Z",
  "user":{"id":"OPAQUE_USER_ID","username":"viewer","administrator":false,"channelIds":["GRANTED_CAMERA_ID"]}
}
```

`token` 是保留的 `accessToken` 别名。访问凭据最多有效 **15 分钟**，媒体和所有读接口都使用 Header：

```http
Authorization: Bearer ACCESS_TOKEN
```

续期凭据每次成功使用后轮换，并从成功续期时间起保持 **30 天**有效。App 使用 Android Keystore AES-GCM 私有存储保存访问凭据、续期凭据、服务器地址和已核对的公钥指纹，不保存密码。网页 Cookie 和 App token 不可跨端口混用。

`POST /api/v1/auth/refresh`，不需要仍有效的 accessToken：

```json
{"refreshToken":"CURRENT_REFRESH_TOKEN"}
```

返回与登录相同结构，同时给出下一枚 `refreshToken`。应串行执行续期，原子保存最新凭据；并发的元数据、实时和回放请求应共享同一次续期结果。访问凭据过期或电脑软件重启后的 401，先续期一次，再重试原请求；续期 401 表示长期登录已撤销或过期，此时停止媒体并返回登录。断网/临时 503 保留密文登录信息，恢复网络后重试，不能把所有连接错误都当作密码失效。

已使用的最近续期凭据再次被提交，会撤销该设备的整个登录族，其他手机和其他用户不受影响。长期凭据也不可放入 URL、日志或明文配置。

`GET /api/v1/auth/me` 返回当前 `username`、`user` 和访问凭据 `expiresAt`。

`POST /api/v1/auth/logout`，Header 可携带当前 accessToken，JSON 可携带 `{ "refreshToken": "CURRENT_REFRESH_TOKEN" }`。即使 accessToken 已过期，仅提交仍存在的 refreshToken 也可退出。退出撤销当前手机的续期与访问凭据并关闭其媒体，返回 `{ "authenticated": false }`。本机随后清除密文登录信息；离线退出只能先清本机信息，服务器会话需到期或由电脑管理员撤销。

更改用户的用户名、密码、摄像头权限、启用状态或删除用户，会立即并持久撤销这个用户的所有 Web/App 登录；重新启动服务器也不会恢复旧权限。普通保存远程设置不清除未受影响的用户会话；关闭对应服务会撤销该服务登录。API 只需有至少一个启用用户即可开启，不必设置管理员密钥。

## 管理员密钥兼容

旧 App API 密钥继续有效，具有全部摄像头和录像的查看权限。它是可选管理员凭据，不应分发给需要限制摄像头的用户。在桌面“App API”页签生成、复制并保存，可继续携带：

```http
Authorization: Bearer YOUR_API_KEY
```

密钥只通过此 Header 传递，不支持 URL 参数或 Cookie。保存后的密钥只存储 SHA-256 摘要，不能再次读取原文；请在生成时复制并保存在 App 的安全存储中。空白的新密钥表示保留已设置的密钥；生成、输入新密钥并保存会替换旧密钥。手动密钥需要 24 至 256 个字符，允许字母、数字和 `. _ ~ + / -`，可带最多两个结尾 `=`；推荐直接使用软件的生成按钮。管理员密钥调用 `/auth/me` 时 `user.administrator` 为 true、`channelIds` 为全部当前设备，`expiresAt` 为 null；对管理员密钥调用 logout 不会删除长期密钥，需要在桌面更换它。

更换管理员密钥后旧密钥立即失效；修改网页管理员凭据会撤销该管理员的网页登录。普通设置保存不会无故清除普通用户登录。

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="Recorder API"
Content-Type: application/json; charset=utf-8

{"error":"unauthorized","message":"登录已失效或 API 密钥无效，请重新登录。"}
```

## 时间和标识

所有绝对时间均为 ISO 8601 UTC，例如 `2026-10-12T01:23:45.123Z`。`server.timezone` 返回录像机本机时区，用于显示本地日期和时间。查询参数必须包含时区；将用户选择的本地时间先转换成带偏移或 UTC 的 ISO 8601 时间。

设备 `id` 和录像 `id` 是稳定的不透明标识。不要自行拼接文件路径、根据文件名推断 ID，或将 ID 当成认证密钥。录像列表仅包含当前注册设备的已登记录像。录像被保留策略清理、设备被移除或尚无可播放的数据时，媒体请求可能返回 404；App 应重新获取录像列表。正在写入且已有正数 durationSeconds 的录像可以回放，不要按 active=true 排除。

## 接口列表

所有下面的路径位于 `https://录像机电脑IP:9079`。同名只读路径也用于 Web 页面，但 8079 使用 Web 会话认证。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | 用户名、密码登录，取得 App token |
| POST | `/api/v1/auth/refresh` | 用当前续期凭据换取最新登录凭据 |
| GET | `/api/v1/auth/me` | 当前用户与可查看的摄像头 |
| POST | `/api/v1/auth/logout` | 持久撤销当前设备登录族 |
| GET | `/api/v1/server` | 录像机时间、版本、公共存储和能力 |
| GET | `/api/v1/cameras` | 设备列表和当前状态 |
| GET | `/api/v1/cameras/{channelId}/live.jpg` | 当前 JPEG 画面 |
| GET | `/api/v1/cameras/{channelId}/live.mjpg` | 连续 MJPEG 预览 |
| GET | `/api/v1/recordings` | 时间区间内的录像和人员事件 |
| GET | `/api/v1/recording-days` | 指定摄像头在录像机时区中的月日历标记 |
| GET | `/api/v1/recording-bounds` | 指定摄像头全部保留录像的最早起点、最晚尾部 |
| GET | `/api/v1/recordings/{recordingId}/media` | 浏览器兼容的 MP4 回放 |
| GET、HEAD | `/api/v1/recordings/{recordingId}/download` | 原始 MKV，支持单个字节范围 |

### GET /api/v1/server

```json
{
  "name": "录像机",
  "apiVersion": "1",
  "serverTime": "2026-10-12T01:23:45.123Z",
  "timezone": "Asia/Shanghai",
  "maxBytes": 21474836480,
  "usedBytes": 5368709120,
  "keepDays": 7,
  "segmentMinutes": 5,
  "capabilities": ["live-jpeg", "live-mjpeg", "recordings", "person-events", "mp4-playback", "original-range", "user-auth", "camera-permissions"]
}
```

`usedBytes` 和 `maxBytes` 是全部已注册设备共用的录像空间。返回字节数，App 自行换算 GB。录像时长和录制质量仍在桌面设置中调整。

### GET /api/v1/cameras

```json
{
  "cameras": [
    {
      "id": "channel-opaque-id",
      "name": "示例摄像头",
      "number": 1,
      "online": true,
      "recording": false,
      "width": 1920,
      "height": 1080,
      "liveUrl": "/api/v1/cameras/channel-opaque-id/live.mjpg"
    }
  ]
}
```

`number` 用于显示 `CH01`；设备 ID 用于请求。`online` 反映当前是否已有可用预览画面，未启动预览时可能为 false。用户打开某个通道时仍可请求实时画面，服务器会按需连接。远程画面不会因为桌面停止预览而一同中断；退出软件、移除设备或设备离线会结束该通道画面。

### 实时 JPEG 和 MJPEG

JPEG 返回 `Content-Type: image/jpeg`，首次连接最多等待约 10 秒。暂时没有画面返回 503 和 `camera_offline`。

MJPEG 返回 `multipart/x-mixed-replace; boundary=nvrframe`，每帧有 `Content-Type: image/jpeg` 与 `Content-Length`。预览最高约 5 帧/秒，最大宽度 1280，保持原比例。相同通道的并发查看共享 JPEG 编码结果。它不带声音；原始录像和 MP4 回放保留可用音频。

一次 MJPEG 连接最多 30 分钟；App 在正常结束后重新连接即可。用户退出、服务关闭或权限变更后，对应连接会结束。App 使用 HTTPS 客户端设置 Authorization Header，解析各帧并在界面更新图片；不要把密钥拼接到图片 URL 中。

### GET /api/v1/recordings

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `channelId` | 否 | 某个设备 ID；不传则查询全部注册设备 |
| `from` | 否 | 区间开始，ISO 8601，默认录像机本地当天 00:00 |
| `to` | 否 | 区间结束，ISO 8601，默认当前时刻 |

`to` 必须晚于 `from`，单次最多查询 31 天。结果包含与区间有交集的录像，而不只包含开始时间落在区间内的录像；边界为 `[from, to)`。单次最多 20,000 条，超过时返回 413，应缩小区间。当前没有分页参数。

```http
GET /api/v1/recordings?channelId=channel-opaque-id&from=2026-10-12T00%3A00%3A00Z&to=2026-10-12T12%3A00%3A00Z
Authorization: Bearer YOUR_API_KEY
```

```json
{
  "from": "2026-10-12T00:00:00Z",
  "to": "2026-10-12T12:00:00Z",
  "recordings": [
    {
      "id": "0123456789abcdef0123456789abcdef",
      "channelId": "channel-opaque-id",
      "start": "2026-10-12T01:00:00Z",
      "end": "2026-10-12T01:05:00Z",
      "durationSeconds": 300.0,
      "bytes": 105000000,
      "active": false,
      "persons": [{"startSeconds": 31.25, "endSeconds": 36.0}],
      "personStatus": "READY",
      "mediaUrl": "/api/v1/recordings/0123456789abcdef0123456789abcdef/media",
      "downloadUrl": "/api/v1/recordings/0123456789abcdef0123456789abcdef/download"
    }
  ]
}
```

`persons` 是相对于该录像 `start` 的秒数区间，绝对事件开始为 `start + startSeconds`，结束为 `start + endSeconds`。用这些区间在连续时间轴上绘制橙色人员事件段。人员识别在录制过程中增量运行，空数组在该区间尚未分析或人物证据不足时不代表已确认无人。

`personStatus`：`PENDING` 等待、`ANALYZING` 正在识别、`READY` 当前已收到窗口分析可用（活动片段后续继续分析）、`FAILED` 识别失败、`DISABLED` 识别关闭。App 可每隔数秒刷新可见区间，保留用户选择的回放时间和缩放尺度。

`active=true` 表示文件尚在录制，已有正数可用时长即可请求媒体。客户端约每2秒刷新活动录像和人员事件，到目前可播尾部等待新数据；无需 App 处理实际录像目录。

### GET /api/v1/recording-days

必填查询参数 `channelId=GRANTED_CAMERA_ID` 与 `month=YYYY-MM`，例如 `2026-10`；月份年份支持 1970–9998。兼容参数 `channel`，但同时提供两个通道参数时必须相同。未知和未授权设备均返回 404。只查询指定设备、指定月，最多处理 20,000 条，超过返回 413。

```json
{
  "channelId": "channel-opaque-id",
  "month": "2026-10",
  "timezone": "Asia/Shanghai",
  "days": [{
    "date": "2026-10-12",
    "hasRecording": true,
    "hasPersons": true,
    "active": true,
    "recordingCount": 2,
    "recordedSeconds": 300.0,
    "personSeconds": 5.0,
    "first": "2026-10-12T01:00:00Z",
    "last": "2026-10-12T01:05:00Z"
  }]
}
```

`date` 按返回的录像机时区计算，与浏览器或手机时区无关，跨午夜录像会标记其实际覆盖的每一天。`days` 按日期升序，只列出文件仍存在且已有正数可播放时长的日期；当天完全没有录像时不返回该日期。边界采用 `[start,end)`，恰好到午夜结束的录像不会多标下一天。

`hasPersons` 与 `personSeconds` 只统计已有人员事件在当天的实际覆盖，事件超出录像已可播放的尾部会被截断；未完成的人员分析不应解释为已确认无人。`active` 只标记正在录制片段的**实际可播放尾部**所属日期，跨午夜片段不会把此前日期也显示为正在录制。`recordingCount` 是与当天有交集的片段数；`recordedSeconds`、`personSeconds` 对重叠区间取并集，避免重复计时，夏令时日期按实际 23／25 小时计算。`first` 是当天最早录像起点，`last` 是当天最晚尾部（不包含该时刻），均为 UTC ISO 绝对时间。响应不含真实文件路径和摄像头连接凭据。

### GET /api/v1/recording-bounds

必填查询参数 `channelId=GRANTED_CAMERA_ID`（兼容 `channel`），权限规则与月日历相同。这是全保留范围的定位查询，不受日历当前月份或普通录像列表的 31 天查询窗口限制。

```json
{
  "channelId": "channel-opaque-id",
  "timezone": "Asia/Shanghai",
  "firstRecording": "2026-09-01T01:00:00Z",
  "lastRecording": "2026-10-12T01:05:00Z"
}
```

只统计文件仍存在且已有正数可播放时长的录像，全部没有时两个字段均为 `null`。`firstRecording` 是最早起点；`lastRecording` 是最晚尾部，采用不包含末尾的边界。最新录像导航应先查询末尾附近的录像列表，再将光标放在该片段内，例如 `max(start, lastRecording - 1秒)`，不要直接在末尾发起回放。最多处理 100,000 条，超过返回 413 并提示按月份查询。

### GET /api/v1/recordings/{id}/media

为浏览器及 App 返回 H.264/AAC fragmented MP4。参数：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `offsetSeconds` | 0 | 录像内部起点，必须 `0 <= offsetSeconds < recording.durationSeconds`，上限为原录像的总时长 |
| `durationSeconds` | 300 | 此次回放窗口，必须大于 0 且不超过 300 秒；自动限制到录像末尾 |

响应为 `Content-Type: video/mp4`，分块传输。Header `X-Playback-Offset-Seconds` 与 `X-Playback-Duration-Seconds` 返回实际请求窗口。浏览器版本最高宽 1280、20 帧/秒；原始录像不被改写。

```http
GET /api/v1/recordings/0123456789abcdef0123456789abcdef/media?offsetSeconds=120&durationSeconds=180
Authorization: Bearer YOUR_API_KEY
```

此接口通过参数选择时间，不支持 HTTP Range。时间轴拖到目标绝对时间 `T` 时，找到满足 `recording.start <= T < recording.end` 的录像，将 `offsetSeconds` 设置为 `T - recording.start`，关闭旧媒体请求并发起新请求。当前窗口播放完后，查询下一段并继续，跨段间隙在时间轴上显示为空档。播放器 `currentPosition` 是当前 MP4 内的位置，显示录像时间时应加上 `start + offsetSeconds`。回放初始少量编码延迟不影响人员事件的原始时间轴。

同一录像、起点及窗口的请求共享一次转码和内存数据。最多两个不同窗口同时转码；超过会返回 503。播放器在拖动时应先取消旧请求，避免积累并发窗口。媒体缓存仅保存在内存，每个窗口上限 128 MiB、最多两个窗口；退出软件即清空，不产生监控画面临时文件。

### GET、HEAD /api/v1/recordings/{id}/download

返回已完成或正在录制且已有可读数据的原始 MKV，适用于 Android Media3/ExoPlayer 保留录像原始分辨率。活动录像固定本次请求开始时已经收到的字节范围，不跟随文件增长。支持 `Accept-Ranges: bytes` 和单个 Range，例如 `bytes=0-65535`、`bytes=65536-` 或 `bytes=-65536`；不支持多范围。Range 成功返回 206 和 `Content-Range`，无 Range 返回 200。HEAD 返回对应的长度和范围 Header，不返回内容。

GET 和 HEAD 均返回 `Content-Disposition: attachment; filename="CH01_20261012_090000_OPAQUE_RECORDING_ID.mkv"`。文件名使用通道编号、录像机时区的录制起点与不透明录像 ID，便于下载后查找，不含实际目录、摄像头地址或凭据；不要依赖该名称自行构造服务端文件路径。

```http
GET /api/v1/recordings/0123456789abcdef0123456789abcdef/download
Authorization: Bearer YOUR_API_KEY
Range: bytes=0-65535
```

```http
HTTP/1.1 206 Partial Content
Content-Type: video/x-matroska
Accept-Ranges: bytes
Content-Range: bytes 0-65535/105000000
Content-Length: 65536
```

无效或超出文件的范围返回 416 及 `Content-Range: bytes */文件总字节数`。回放和下载期间，服务器保护该文件不被保留策略删除；客户端应及时取消离开的媒体请求。

## Web 会话接口（仅 8079）

8079 的 `/api/v1/session`：

| 方法 | 内容 | 返回 |
| --- | --- | --- |
| POST | JSON `{"username":"admin","password":"..."}` | 登录，设置 `NVR_SESSION` Cookie，返回 `authenticated`、`username`、`csrfToken` |
| GET | 有效 Cookie | 返回当前登录和 CSRF token，未登录返回 401 与 `{"authenticated":false}` |
| DELETE | Cookie 加 `X-CSRF-Token` | 退出，删除 Cookie，返回 `{"authenticated":false}` |

Cookie 使用 `Secure; HttpOnly; SameSite=Strict; Path=/`。登录JSON可提供 `remember:true`（默认），Cookie和受保护服务器会话最长30天且跨服务器重启；`remember:false`不设Max-Age、不持久化，最多12小时且30分钟空闲失效。GET/POST会话响应包含remember和expiresAt。浏览器写请求检查同源 Origin，退出操作额外检查 CSRF token。当前接口只有登录和退出需要写请求。每个客户端 IP 在一分钟内最多允许 8 次失败登录，再次尝试返回 429，使用 `Retry-After: 60`。验证成功会清除该客户端的失败计数。

## 错误及资源限制

除二进制媒体外，错误统一为：

```json
{"error":"machine_readable_code","message":"给用户显示的中文说明"}
```

| 状态码 | 常见原因 | App 处理 |
| --- | --- | --- |
| 400 | 时间、偏移或 JSON 无效，时间区间超过 31 天 | 检查并修改请求参数 |
| 401 | 访问凭据过期、密钥无效或账号已更新 | access 401先续期一次；refresh 401停止媒体并回登录 |
| 403 | Web 来源或 CSRF 无效 | 刷新 Web 登录状态 |
| 404 | 资源不存在、没有权限、录像暂无可播数据或已清理 | 刷新设备／录像索引 |
| 405 | 接口方法不支持，或尝试远程写操作 | 使用文档中的只读方法 |
| 413 | JSON 超过 16 KiB，或录像结果超过 20,000 条 | 缩小请求／时间区间 |
| 415 | Web 登录不是 application/json | 调整 Content-Type |
| 416 | 原始文件 Range 无效 | 重新读取 HEAD 和文件长度 |
| 429 | 查看连接、登录次数或会话数量达到上限 | 关闭旧连接，等待后重试 |
| 503 | 摄像头暂时无画面、转码忙或组件暂时不可用 | 稍后重试，并保留当前位置 |

实时画面、MP4 和下载合计最多 24 条在途请求，支持九分屏加回放。Web/App访问会话合计最多128个、已保存手机登录族最多128个、并行密码验证2个、在途请求最多64个。App切换页面、通道或停止播放时须取消旧媒体连接。服务器不开放跨域CORS；Android原生HTTPS客户端遵守相同认证与媒体权限。

机器可读协议见 [openapi.yaml](openapi.yaml)。

## 正在录制片段与低延迟人员事件

`active:true` 表示文件还在写入，不表示禁止回放。`durationSeconds` 是已经收到并可播放的媒体时长，保留少量容器写入余量；摄像头不发送时不会随电脑时钟虚增。`end` 始终由 `start + durationSeconds` 计算。

播放活动录像时，按现有 MP4 窗口请求已可用的区间；每个窗口最多 300 秒。达到目前可播尾部但 active 仍为 true 时，保留当前播放意图，约每 2 秒刷新录像元数据，在新时长出现后从原位置续播。暂停时刷新不能推进光标或恢复播放。封段后跨文件接续；空档跳到后续有录像的区间，查询边界以用户选择的范围为限。元数据响应延迟时需检查当前通道、模式和播放代次，不能把旧响应恢复到已切换的画面。

人员列表 `persons` 在活动录像期间增量更新，仍是相对于录像起点的秒数。`personStatus` 为 PENDING/ANALYZING 时不能解释成“无人”；已有部分标记仍可显示。不同时间段未出现橙色不保证没有人。后台/不可见页面应暂停无用轮询，返回后刷新并恢复原暂停位置。

活动原始下载固定本次请求开始时的已收到字节范围，不跟随文件持续增长。文件使用租约保护，在回放/下载期间不会被自动清理。拒绝越权请求不会取得录像租约、唤醒摄像头或启动转码。

## 使用 PowerShell 调用

先将自己电脑导出的公钥 CA 证书安装到当前用户受信任根证书（或为开发语言建立该 CA 的专用信任库）。以下示例使用正常 HTTPS 验证，不忽略证书错误：

```powershell
$recorderBase = 'https://RECORDER_HOST:9079'
$loginBody = @{ username='viewer'; password='YOUR_PASSWORD' } | ConvertTo-Json
$session = Invoke-RestMethod -Uri "$recorderBase/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$requestHeaders = @{ Authorization="Bearer $($session.accessToken)" }
$cameras = Invoke-RestMethod -Uri "$recorderBase/api/v1/cameras" -Headers $requestHeaders

$renewBody = @{ refreshToken=$session.refreshToken } | ConvertTo-Json
$session = Invoke-RestMethod -Uri "$recorderBase/api/v1/auth/refresh" -Method Post -ContentType 'application/json' -Body $renewBody
# 每次续期后更新 Header，并把新 refreshToken 安全保存；不要输出完整 $session。
$requestHeaders = @{ Authorization="Bearer $($session.accessToken)" }
```

接口错误使用 `error` 和中文 `message`。401 为登录失效；403 为来源/退出会话不匹配；404 为资源不存在或没有权限（不泄露其他用户资源）；429 为并发或登录尝试限制；503 为设备/服务暂时不可用。登录请求最多 16 KB，同一来源失败尝试受限，密码校验、实时观看和转码均有独立并发限制。
