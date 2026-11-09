import { env } from "../../lib/env";
import { AppError } from "../../lib/errors";
import type { AuthSession } from "../../types/domain";
import { mapLoginResponse } from "./api-service";

export interface HttpClientOptions {
  getToken: () => string | null;
  getRefreshToken?: () => string | null;
  onSessionRefreshed?: (session: AuthSession) => void;
  onUnauthorized: () => void;
}

const NON_REFRESHABLE_PATHS = [
  "/auth/login",
  "/auth/refresh",
  "/auth/logout",
  "/invitations/accept",
];

const isRefreshEligible = (path: string, hasRetried: boolean): boolean => {
  if (hasRetried) return false;
  const pathWithoutQuery = path.split("?")[0] ?? path;
  return !NON_REFRESHABLE_PATHS.includes(pathWithoutQuery);
};

const buildAppError = (
  response: Response,
  rawBody: string,
  hasBody: boolean,
): AppError => {
  const fallback = {
    status: response.status,
    code: "HTTP_ERROR",
    message:
      response.statusText || `Request failed with status ${response.status}`,
  };

  let payload = fallback;
  if (hasBody) {
    try {
      const parsed = JSON.parse(rawBody) as Partial<{
        status: number;
        code: string;
        message: string;
        path: string;
      }>;
      payload = { ...fallback, ...parsed };
    } catch {
      payload = {
        ...fallback,
        message: rawBody.trim() || fallback.message,
      };
    }
  }

  return new AppError(payload);
};

export const createHttpClient = (options: HttpClientOptions) => {
  let refreshPromise: Promise<AuthSession> | null = null;

  const performRefresh = async (refreshToken: string): Promise<AuthSession> => {
    const refreshResponse = await fetch(`${env.apiBaseUrl}/auth/refresh`, {
      method: "POST",
      headers: new Headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({ refreshToken }),
    });

    const rawBody =
      refreshResponse.status === 204 ? "" : await refreshResponse.text();
    const hasBody = rawBody.trim().length > 0;

    if (!refreshResponse.ok) {
      throw buildAppError(refreshResponse, rawBody, hasBody);
    }

    if (!hasBody) {
      throw new AppError({
        status: refreshResponse.status,
        code: "INVALID_JSON_RESPONSE",
        message: "Expected a JSON response body.",
      });
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(rawBody);
    } catch {
      throw new AppError({
        status: refreshResponse.status,
        code: "INVALID_JSON_RESPONSE",
        message: "Expected a JSON response body.",
      });
    }

    const nextSession = mapLoginResponse(parsed);
    options.onSessionRefreshed?.(nextSession);
    return nextSession;
  };

  const execute = async <T>(
    path: string,
    init?: RequestInit,
    hasRetried = false,
    tokenOverride?: string,
  ): Promise<T> => {
    const token = tokenOverride ?? options.getToken();
    const headers = new Headers(init?.headers);
    headers.set("Content-Type", "application/json");
    if (token) headers.set("Authorization", `Bearer ${token}`);

    const response = await fetch(`${env.apiBaseUrl}${path}`, {
      ...init,
      headers,
    });

    const rawBody = response.status === 204 ? "" : await response.text();
    const hasBody = rawBody.trim().length > 0;

    if (!response.ok) {
      if (response.status === 401) {
        const refreshToken = options.getRefreshToken?.() ?? null;
        if (refreshToken && isRefreshEligible(path, hasRetried)) {
          if (!refreshPromise) {
            refreshPromise = performRefresh(refreshToken)
              .catch((refreshError) => {
                if (
                  refreshError instanceof AppError &&
                  (refreshError.status === 401 || refreshError.status === 403)
                ) {
                  options.onUnauthorized();
                }
                throw refreshError;
              })
              .finally(() => {
                refreshPromise = null;
              });
          }

          const refreshedSession = await refreshPromise;
          return execute<T>(path, init, true, refreshedSession.token);
        }

        options.onUnauthorized();
      }

      const appError = buildAppError(response, rawBody, hasBody);
      throw appError;
    }

    if (!hasBody) {
      return undefined as T;
    }

    try {
      return JSON.parse(rawBody) as T;
    } catch {
      throw new AppError({
        status: response.status,
        code: "INVALID_JSON_RESPONSE",
        message: "Expected a JSON response body.",
      });
    }
  };

  return function http<T>(path: string, init?: RequestInit): Promise<T> {
    return execute<T>(path, init);
  };
};
