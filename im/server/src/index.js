import http from 'node:http';
import { loadConfig } from './config.js';
import { createApplication } from './app.js';
import { RealtimeHub } from './realtime.js';

const config = loadConfig();
const { app, db } = createApplication(config);
const server = http.createServer(app);
const realtime = new RealtimeHub(server, db, config);
app.locals.realtime = realtime;

server.listen(config.port, config.host, () => {
  console.info(`LinkUp IM server listening on http://${config.host}:${config.port}`);
});

function shutdown() {
  realtime.close();
  server.close(() => {
    db.close();
    process.exit(0);
  });
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
