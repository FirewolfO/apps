FROM node:22-bookworm-slim

ENV NODE_ENV=production
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src
RUN mkdir -p /app/data/uploads && chown -R node:node /app

USER node
EXPOSE 3000
CMD ["node", "src/index.js"]
