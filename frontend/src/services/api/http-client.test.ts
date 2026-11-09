import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppError } from "../../lib/errors";
import { createHttpClient } from "./http-client";

const mockFetch = vi.fn<typeof fetch>();
vi.stubGlobal("fetch", mockFetch);

describe("http client", () => {
  beforeEach(() => {
    mockFetch.mockReset();
  });

  it("returns undefined for 204 responses", async () => {
    mockFetch.mockResolvedValueOnce(new Response(null, { status: 204 }));

    const onUnauthorized = vi.fn();
    const http = createHttpClient({ getToken: () => null, onUnauthorized });

    await expect(http<void>("/empty")).resolves.toBeUndefined();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("returns undefined for empty 200 responses", async () => {
    mockFetch.mockResolvedValueOnce(new Response("", { status: 200 }));

    const http = createHttpClient({
      getToken: () => "token-1",
      onUnauthorized: vi.fn(),
    });

    await expect(http<void>("/empty-ok")).resolves.toBeUndefined();
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining("/empty-ok"),
      expect.objectContaining({
        headers: expect.any(Headers),
      }),
    );
  });

  it("returns parsed JSON for non-empty successful responses", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    const http = createHttpClient({
      getToken: () => null,
      onUnauthorized: vi.fn(),
    });

    await expect(http<{ ok: boolean }>("/ok")).resolves.toEqual({ ok: true });
  });

  it("preserves backend error envelope for JSON errors", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          status: 422,
          code: "JOURNAL_ENTRY_UNBALANCED",
          message: "Journal entry debits and credits must be equal",
          path: "/journal-entries/10/post",
        }),
        { status: 422, headers: { "Content-Type": "application/json" } },
      ),
    );

    const http = createHttpClient({
      getToken: () => null,
      onUnauthorized: vi.fn(),
    });

    await expect(http("/journal-entries/10/post")).rejects.toMatchObject({
      status: 422,
      code: "JOURNAL_ENTRY_UNBALANCED",
      message: "Journal entry debits and credits must be equal",
    });
  });

  it("keeps status information for non-JSON errors", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response("upstream timeout", {
        status: 503,
        statusText: "Service Unavailable",
      }),
    );

    const http = createHttpClient({
      getToken: () => null,
      onUnauthorized: vi.fn(),
    });

    const errorPromise = http("/reports");
    await expect(errorPromise).rejects.toBeInstanceOf(AppError);
    await expect(errorPromise).rejects.toMatchObject({
      status: 503,
      code: "HTTP_ERROR",
      message: "upstream timeout",
    });
  });

  it("distinguishes 401 and triggers unauthorized callback", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: "UNAUTHORIZED",
          message: "Authentication required",
        }),
        {
          status: 401,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );

    const onUnauthorized = vi.fn();
    const http = createHttpClient({ getToken: () => null, onUnauthorized });

    await expect(http("/accounts")).rejects.toMatchObject({
      status: 401,
      code: "UNAUTHORIZED",
    });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it("keeps 403 distinguishable from 401", async () => {
    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({ code: "FORBIDDEN", message: "No access" }),
        {
          status: 403,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );

    const onUnauthorized = vi.fn();
    const http = createHttpClient({ getToken: () => null, onUnauthorized });

    await expect(http("/users")).rejects.toMatchObject({
      status: 403,
      code: "FORBIDDEN",
    });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("refreshes the session on 401 and replays the failed request with the new token", async () => {
    let accessToken: string | null = "expired-access-token";
    let refreshToken: string | null = "refresh-token-1";
    const onUnauthorized = vi.fn();
    const onSessionRefreshed = vi.fn((session) => {
      accessToken = session.token;
      refreshToken = session.refreshToken ?? null;
    });

    mockFetch
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ code: "UNAUTHORIZED", message: "Expired" }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            token: "fresh-access-token",
            expiresAt: "2026-11-10T12:00:00Z",
            refreshToken: "refresh-token-2",
            refreshExpiresAt: "2026-11-17T12:00:00Z",
            user: {
              id: 7,
              email: "admin@ledger.test",
              fullName: "Admin User",
              role: "ADMIN",
              organizationId: 42,
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify([{ id: 1, name: "Cash" }]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

    const http = createHttpClient({
      getToken: () => accessToken,
      getRefreshToken: () => refreshToken,
      onSessionRefreshed,
      onUnauthorized,
    });

    const accounts = await http<Array<{ id: number; name: string }>>("/accounts");
    expect(accounts).toEqual([{ id: 1, name: "Cash" }]);
    expect(mockFetch).toHaveBeenCalledTimes(3);

    expect(mockFetch.mock.calls[1][0]).toContain("/auth/refresh");
    expect(JSON.parse(String(mockFetch.mock.calls[1][1]?.body))).toEqual({
      refreshToken: "refresh-token-1",
    });

    const retryHeaders = mockFetch.mock.calls[2][1]?.headers as Headers;
    expect(retryHeaders.get("Authorization")).toBe("Bearer fresh-access-token");

    expect(onSessionRefreshed).toHaveBeenCalledTimes(1);
    expect(refreshToken).toBe("refresh-token-2");
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("coalesces concurrent 401 responses into a single refresh request", async () => {
    let accessToken = "expired-token";
    let refreshToken = "refresh-token-1";
    const onUnauthorized = vi.fn();
    const onSessionRefreshed = vi.fn((session) => {
      accessToken = session.token;
      refreshToken = session.refreshToken ?? refreshToken;
    });

    mockFetch.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return new Response(
          JSON.stringify({
            token: "rotated-jwt",
            expiresAt: "2026-11-10T12:00:00Z",
            refreshToken: "refresh-token-2",
            refreshExpiresAt: "2026-11-17T12:00:00Z",
            user: {
              id: 7,
              email: "admin@ledger.test",
              fullName: "Admin User",
              role: "ADMIN",
              organizationId: 42,
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }

      const authHeader = (init?.headers as Headers).get("Authorization");
      if (authHeader === "Bearer expired-token") {
        return new Response(
          JSON.stringify({ code: "UNAUTHORIZED", message: "Expired" }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        );
      }

      return new Response(JSON.stringify({ ok: url }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });

    const http = createHttpClient({
      getToken: () => accessToken,
      getRefreshToken: () => refreshToken,
      onSessionRefreshed,
      onUnauthorized,
    });

    const [resA, resB] = await Promise.all([
      http<{ ok: string }>("/accounts"),
      http<{ ok: string }>("/organizations/me"),
    ]);

    expect(resA.ok).toContain("/accounts");
    expect(resB.ok).toContain("/organizations/me");

    const refreshCalls = mockFetch.mock.calls.filter(([input]) =>
      String(input).endsWith("/auth/refresh"),
    );
    expect(refreshCalls).toHaveLength(1);
    expect(onSessionRefreshed).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("calls onUnauthorized once when refresh fails with 401 and skips refresh on /auth/login", async () => {
    const onUnauthorized = vi.fn();

    mockFetch
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ code: "UNAUTHORIZED", message: "Expired" }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ code: "UNAUTHORIZED", message: "Refresh revoked" }),
          { status: 401, headers: { "Content-Type": "application/json" } },
        ),
      );

    const http = createHttpClient({
      getToken: () => "expired-jwt",
      getRefreshToken: () => "revoked-refresh",
      onUnauthorized,
    });

    await expect(http("/accounts")).rejects.toMatchObject({
      status: 401,
      code: "UNAUTHORIZED",
    });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);

    mockFetch.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: "UNAUTHORIZED",
          message: "Invalid email or password",
        }),
        { status: 401, headers: { "Content-Type": "application/json" } },
      ),
    );

    await expect(
      http("/auth/login", { method: "POST", body: "{}" }),
    ).rejects.toMatchObject({
      status: 401,
      message: "Invalid email or password",
    });
    expect(
      mockFetch.mock.calls.filter(([input]) =>
        String(input).endsWith("/auth/refresh"),
      ),
    ).toHaveLength(1);
  });

});
