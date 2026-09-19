# syntax=docker/dockerfile:1
# 관리자 콘솔 이미지: vite 빌드 결과를 nginx로 서빙하고 /admin/** API는 백엔드로 프록시한다(nginx.conf).
# 빌드 컨텍스트는 admin/.

# ── build ──
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ── runtime ──
FROM nginx:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
