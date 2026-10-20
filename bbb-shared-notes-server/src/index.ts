import { init as initErrorTracking, flush as flushErrorTracking } from "./common/error-tracking";
import startRedis from "./redis/subscriber";
import { startExpressApp } from "./express";
import { removeExpiredDocuments } from "./hocuspocus/extensions/postgresql";
import { Logger } from "./common/logger";
import config from "./config";

const logger = new Logger('cleanup');
initErrorTracking('bbb-shared-notes-server', { logger, config: config.sentry });

async function main() {
  await startRedis();
  startExpressApp();
}

setInterval(async () => {
  try {
    const removed = await removeExpiredDocuments();
    if (removed > 0) {
      logger.info(`Removed ${removed} expired document(s)`);
    }
  } catch (error) {
    logger.error('Failed to remove expired documents', { error });
  }
}, 60_000);

try {
  await main();
} catch (err) {
  logger.error('Fatal startup error', { error: err });
  await flushErrorTracking();
  process.exit(1);
}
