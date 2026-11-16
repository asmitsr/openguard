# Self-host image for the Node entry point (src/server.ts). The Cloudflare
# Workers path (wrangler deploy) does not use this file at all.
# Build context is the REPO ROOT (see docker-compose.yml): the image mirrors
# the repo layout (/app/backend + /app/db) so backend/scripts/migrate.mjs
# finds db/ at ../../db exactly as it does in the repo.

FROM node:22-alpine AS build
WORKDIR /app/backend
COPY backend/package.json backend/package-lock.json ./
RUN npm ci
COPY backend/web/package.json backend/web/package-lock.json ./web/
RUN npm ci --prefix web
COPY backend/ ./
# Build the React dashboard first: src/index.ts inlines web/dist/index.html,
# so it must exist before the API bundle step (a clean checkout has no dist/).
RUN npm --prefix web run build
# tsc typecheck, then bundle the Node entry. Plain tsc cannot emit the
# `import dashboardHtml from '../web/dist/index.html'` in src/index.ts, so
# esbuild bundles it (--loader:.html=text inlines the dashboard, as the
# Worker bundle does).
RUN npm run build && npm run build:node

FROM node:22-alpine
WORKDIR /app/backend
ENV NODE_ENV=production
COPY --from=build /app/backend/dist-node ./dist-node
COPY --from=build /app/backend/scripts ./scripts
COPY --from=build /app/backend/package.json /app/backend/package-lock.json ./
# Production deps only: migrate.mjs imports `postgres` at runtime (the API
# bundle already contains its own copy).
RUN npm ci --omit=dev
COPY db/ /app/db/
USER node
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:8787/api/v1/health || exit 1
CMD ["node", "dist-node/server.js"]
