import { createApplication } from './app.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const app = await createApplication(config);
app.listen(config.port, config.host, () => {
  console.log(`App Center listening on ${config.host}:${config.port}`);
});
