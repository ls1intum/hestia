# Multi-stage build for the HESTIA landing page (static React/Vite site, no backend).
# The runtime image is nginx serving the static build. Build context is this directory
# (apps/landing-page).

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
