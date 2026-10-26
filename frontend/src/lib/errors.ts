import type { ApiError } from '../types/domain';

export class AppError extends Error {
  status: number;
  code: string;

  constructor(payload: ApiError) {
    super(payload.message);
    this.status = payload.status;
    this.code = payload.code;
  }
}

export class UnsupportedInApiModeError extends Error {
  constructor(message: string) {
    super(message);
  }
}
