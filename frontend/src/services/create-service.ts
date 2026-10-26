import { env } from "../lib/env";
import { createHttpClient } from "./api/http-client";
import { createApiService } from "./api/api-service";
import { createMockService } from "./mock/mock-service";
import { type LedgerService } from "./interfaces/ledger-service";
import type { AuthSession } from "../types/domain";

interface CreateServiceOptions {
  getToken: () => string | null;
  onUnauthorized: () => void;
  currentSession: AuthSession | null;
}

export const createService = ({
  getToken,
  onUnauthorized,
  currentSession,
}: CreateServiceOptions): LedgerService => {
  if (env.mode === "api") {
    const http = createHttpClient({ getToken, onUnauthorized });
    return createApiService(http, currentSession);
  }

  return createMockService(currentSession);
};
