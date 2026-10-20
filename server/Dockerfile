# Stage 1: Web dashboard builder
FROM node:22-alpine AS web-builder
WORKDIR /app/dashboard

COPY dashboard/package*.json ./
RUN npm ci

COPY dashboard/ ./
RUN npm run build

# Stage 2: API builder
FROM node:22-alpine AS api-builder
WORKDIR /app

RUN apk add --no-cache python3 make g++

COPY package*.json tsconfig.json ./
RUN npm ci

COPY src ./src
COPY migrations ./migrations
RUN npm run build:api

# Stage 3: Production runner
FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0
ENV DATA_DIR=/app/data

RUN apk add --no-cache python3 make g++

COPY package*.json ./
RUN npm ci --omit=dev

# Copy compiled backend and frontend assets
COPY --from=api-builder /app/dist ./dist
COPY --from=api-builder /app/migrations ./migrations
COPY --from=web-builder /app/public ./public

# Setup persistent data folder owned by non-root node user
RUN mkdir -p /app/data && chown -R node:node /app/data /app/public

USER node
VOLUME ["/app/data"]
EXPOSE 3000

CMD ["node", "dist/index.js"]
