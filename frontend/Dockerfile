# ============================================================
# 前端镜像：Vite 构建静态资源 + Nginx 托管并反代 /api 到后端
#
# 构建（上下文是 frontend 目录）：
#   docker build -t lion-agent-frontend:1.0.0 ./frontend
#   如需自定义构建期接口地址：--build-arg VITE_API_BASE_URL=/api
#
# 运行：
#   docker run -d --name lion-frontend -p 80:80 \
#     -e BACKEND_UPSTREAM=backend:8080 \   # 后端地址（容器内服务名:端口）
#     lion-agent-frontend:1.0.0
# ============================================================

# ---------------- 阶段一：Node 构建 ----------------
FROM node:22-alpine AS builder

WORKDIR /app

# 先装依赖，锁文件不变时命中缓存
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
# 可通过 --build-arg 覆盖接口前缀，默认 /api（由 Nginx 反代，同域部署无需改动）
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---------------- 阶段二：Nginx 托管 ----------------
FROM nginx:1.27-alpine

# 后端上游地址，容器启动时由官方 nginx 镜像的 envsubst 渲染进配置
ENV BACKEND_UPSTREAM=backend:8080

COPY docker/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
