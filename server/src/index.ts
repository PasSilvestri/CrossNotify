import { createApp } from './app.js';

async function main() {
  try {
    const { app, config, services } = await createApp();

    // Print setup banner if in NEEDS_ADMIN
    services.setupService.printSetupBanner();

    app.listen(config.port, config.host, () => {
      console.log(`[server] CrossNotify Server running on http://${config.host}:${config.port}`);
      console.log(`[server] Current state: ${services.setupService.getState()}`);
    });
  } catch (err) {
    console.error('[server] Fatal error during server startup:', err);
    process.exit(1);
  }
}

main();
