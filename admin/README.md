# 若依后台管理端（RuoYi-Vue3）

独立 clone 的第三方前端，不入版本控制（见同级 `.gitignore`）。

| 项 | 值 |
| --- | --- |
| 来源 | `https://gitcode.com/yangzongzhuan/RuoYi-Vue3` |
| 版本 | 3.9.2 |
| 技术栈 | Vue 3.5.26 + Vite 6.4.1 + Element Plus 2.13.1 |
| dev 端口 | 80（改端口见下） |
| 接口代理 | `/dev-api` → `http://localhost:8080` |

## 启动

```bash
# 在仓库根执行
cd admin/RuoYi-Vue3
npm install          # 已装过，可跳过
npm run dev
```

访问 `http://localhost`。默认账号 `admin / admin123`。

## 两个常见问题

**80 端口绑不上**：Windows 上非管理员可能无法绑定 80。
改 `vite.config.js` 的 `server.port` 为 8081，访问地址同步改成 `http://localhost:8081`。

**登录提示验证码错误，但没显示验证码**：说明后端没起来或连不上。
前端代理目标是 `vite.config.js` 顶部的 `baseUrl`（默认 `http://localhost:8080`），
先确认后端在那。

## 生产构建

```bash
npm run build:prod     # 产物在 dist/
```

> 打包日志会打印 `dist/C:/Users/.../static/...` 这种带绝对路径的行，
> 那是 `vite-plugin-compression` 的日志拼接问题，**实际 `dist/` 结构正常**
> （`index.html` + `static/css|js|png...`），别当成 bug 去改配置。
