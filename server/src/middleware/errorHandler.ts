import type { Request, Response, NextFunction } from 'express';

export function errorHandler(err: any, req: Request, res: Response, next: NextFunction): void {
  if (res.headersSent) {
    return next(err);
  }

  if (err && typeof err.status === 'number' && err.code) {
    res.status(err.status).json({
      error: err.code,
      detail: err.message,
    });
    return;
  }

  console.error('[errorHandler] Uncaught error:', err);
  res.status(500).json({
    error: 'internal_server_error',
    message: err?.message || 'An unexpected error occurred',
  });
}
