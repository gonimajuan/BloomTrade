# BloomTrade — Frontend (React 18 + TS + Vite, servido por nginx)
# Multi-stage: builder con Node 20 → runtime con nginx:alpine sirviendo dist/.

# ─── Stage 1: builder ─────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /build

COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund

COPY . .
RUN npm run build

# ─── Stage 2: runtime nginx ───────────────────────────────────────────────────
FROM nginx:alpine
COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
    CMD wget --quiet --spider http://localhost/ || exit 1
