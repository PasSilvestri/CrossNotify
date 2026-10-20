import type { Request, Response, NextFunction } from 'express';
import { z } from 'zod';

export function validateBody(schema: z.ZodTypeAny) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const result = schema.safeParse(req.body);
    if (!result.success) {
      const issue = result.error.issues[0];
      const message = issue ? `${issue.path.join('.')}: ${issue.message}` : 'Invalid request body';
      res.status(422).json({
        error: 'validation_error',
        detail: message,
        issues: result.error.issues,
      });
      return;
    }
    req.body = result.data;
    next();
  };
}

export function validateQuery(schema: z.ZodTypeAny) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const result = schema.safeParse(req.query);
    if (!result.success) {
      const issue = result.error.issues[0];
      const message = issue ? `${issue.path.join('.')}: ${issue.message}` : 'Invalid query parameters';
      res.status(422).json({
        error: 'validation_error',
        detail: message,
      });
      return;
    }
    for (const key of Object.keys(req.query)) {
      delete (req.query as any)[key];
    }
    Object.assign(req.query, result.data);
    next();
  };
}
