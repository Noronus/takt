FROM node:24-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends unzip zip osmium-tool ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server/package.json server/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY server/src ./src
ENV DATA_DIR=/data TZ=Europe/Berlin NODE_ENV=production NODE_OPTIONS=--max-old-space-size=3072
EXPOSE 8080
CMD ["node", "src/main.ts"]
