# R1XiaoZhi_MediaServer —— R1 小智的媒体服务器（一个容器，一个端口 8100）
#
#   2026-10-17 由两个容器合并而来：
#     · mini-lx   8100（Node）            在线音源：搜索 / 取链 / 随机池
#     · r1-music  8010（Python + Flask + mutagen） 本地音乐库 + 网络电台
#   合并后本地曲库用 Node 重写（local.js，标签读 music-metadata），
#   8010 整个退休 —— R1 的 files/music_index 只写一个地址就够。
#
# 构建：  docker build -t r1-media:local .
# 运行：  docker compose up -d --build
FROM node:20-alpine

# busybox wget 用于 healthcheck；tzdata 让日志时间对（可选）
RUN apk add --no-cache wget tzdata

WORKDIR /app

# 只有 music-metadata 一个依赖（读本地音频内嵌标签；纯 JS，不需要编译工具链）
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev --no-audit --no-fund 2>/dev/null || npm i --omit=dev --no-audit --no-fund

COPY . .

# files/ 是运行时目录（flag 账本 / pool.json / 本地曲库标签缓存 / radios.json），
# compose 里挂卷持久化
RUN mkdir -p /app/files && chown -R node:node /app/files

USER node

EXPOSE 8100

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8100/api/health >/dev/null 2>&1 || exit 1

# 端口与 NAS 上现有部署对齐（8100）；换端口改这里 + compose 两处
CMD ["node", "server.js", "--port", "8100"]
