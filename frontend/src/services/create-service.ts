import { env } from "../lib/env";
import { createHttpClient } from "./api/http-client";
import { type ApiHttp, createApiService } from "./api/api-service";
import { createMockService } from "./mock/mock-service";
import { type LedgerService } from "./interfaces/ledger-service";
import type { AuthSession } from "../types/domain";

interface CreateServiceOptions {
  getToken: () => string | null;
  getRefreshToken?: () => string | null;
  onSessionRefreshed?: (session: AuthSession) => void;
  onUnauthorized: () => void;
  currentSession: AuthSession | null;
  httpClient?: ApiHttp;
}

export const createService = ({
  getToken,
  getRefreshToken,
  onSessionRefreshed,
  onUnauthorized,
  currentSession,
  httpClient,
}: CreateServiceOptions): LedgerService => {
  if (env.mode === "api") {
    const http =
      httpClient ??
      createHttpClient({
        getToken,
        getRefreshToken,
        onSessionRefreshed,
        onUnauthorized,
      });
    return createApiService(http, currentSession);
  }

  return createMockService(currentSession);
};
