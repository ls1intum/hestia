# Multi-stage build for the ExamLense React/Vite frontend.
# The runtime image is nginx: it serves the static build and proxies /api to the server.
# Build context is this directory (apps/examlense/frontend).

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY . .
# `--mode production` is the default for `vite build`; .env.production is baked in here
# (VITE_API_BASE_URL=/examlense, base=/examlense/).
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
