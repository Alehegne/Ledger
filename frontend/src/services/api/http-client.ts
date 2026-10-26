import { env } from "../../lib/env";
import { AppError } from "../../lib/errors";

interface HttpClientOptions {
  getToken: () => string | null;
  onUnauthorized: () => void;
}

export const createHttpClient = (options: HttpClientOptions) => {
  return async function http<T>(path: string, init?: RequestInit): Promise<T> {
    const token = options.getToken();
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
      const fallback = {
        status: response.status,
        code: "HTTP_ERROR",
        message:
          response.statusText ||
          `Request failed with status ${response.status}`,
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

      if (response.status === 401) {
        options.onUnauthorized();
      }

      throw new AppError(payload);
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
};
