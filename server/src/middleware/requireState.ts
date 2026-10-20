import type { Request, Response, NextFunction } from 'express';
import type { SetupService, SetupState } from '../services/setupService.js';

export function requireState(setupService: SetupService, ...allowedStates: SetupState[]) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const currentState = setupService.getState();
    if (!allowedStates.includes(currentState)) {
      res.status(409).json({
        error: 'invalid_state',
        state: currentState,
      });
      return;
    }
    next();
  };
}
