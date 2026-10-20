import express, { type Express, type Request, type Response, type NextFunction } from 'express';
import path from 'node:path';
import fs from 'node:fs';

export function setupStaticServing(app: Express, publicDir: string): void {
  const assetsDir = path.join(publicDir, 'assets');
  if (fs.existsSync(assetsDir)) {
    app.use(
      '/assets',
      express.static(assetsDir, {
        index: false,
        maxAge: '1y',
        immutable: true,
      })
    );
  }

  // Serve other static files in public root (favicon, icons, etc.) if existing
  if (fs.existsSync(publicDir)) {
    app.use(
      express.static(publicDir, {
        index: false,
        maxAge: '1h',
      })
    );
  }

  // SPA fallback
  app.use((req: Request, res: Response, next: NextFunction) => {
    // Check if this is an API route or health check
    if (
      req.path.startsWith('/api/') ||
      req.path.startsWith('/admin/api/') ||
      req.path === '/health'
    ) {
      res.status(404).json({ error: 'not_found', path: req.path });
      return;
    }

    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.status(404).json({ error: 'not_found' });
      return;
    }

    const indexPath = path.join(publicDir, 'index.html');
    if (fs.existsSync(indexPath)) {
      res.setHeader('Cache-Control', 'no-cache');
      res.sendFile(indexPath);
      return;
    }

    // If dashboard is not built yet
    res.status(404).send('CrossNotify dashboard not built yet. Run npm run build.');
  });
}
