FROM node:22-alpine

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY public ./public
COPY src ./src
RUN mkdir -p /data && chown node:node /data

ENV NODE_ENV=production DATA_DIR=/data
EXPOSE 3000
USER node
CMD ["npm", "start"]
