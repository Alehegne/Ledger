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
});
