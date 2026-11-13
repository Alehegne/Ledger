# Stage 1: Build Frontend Static Bundle
FROM node:22-alpine AS builder
WORKDIR /app

# Cache dependencies
COPY package.json package-lock.json ./
RUN npm ci

# Copy source and build for production
COPY . .

# Build arguments for production mode & API routing
ARG VITE_DATA_MODE=api
ARG VITE_API_BASE_URL=""
ENV VITE_DATA_MODE=${VITE_DATA_MODE}
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

RUN npm run build

# Stage 2: Hardened Unprivileged Nginx Runtime
FROM nginxinc/nginx-unprivileged:alpine AS runtime

# Copy build artifacts to nginx public directory
COPY --from=builder /app/dist /usr/share/nginx/html

# Replace default server configuration with SPA and reverse-proxy rules
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=5s \
  CMD wget -q --spider http://127.0.0.1:8080/health || exit 1

CMD ["nginx", "-g", "daemon off;"]
