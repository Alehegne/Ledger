import { beforeEach, describe, expect, it, vi } from "vitest";

describe("env mode resolution", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.unstubAllEnvs();
  });

  it("keeps mock mode as default", async () => {
    const { env } = await import("./env");
    expect(env.mode).toBe("mock");
  });

  it("requires explicit api mode", async () => {
    vi.stubEnv("VITE_DATA_MODE", "api");
    const { env } = await import("./env");
    expect(env.mode).toBe("api");
  });

  it("fails clearly on invalid mode", async () => {
    vi.stubEnv("VITE_DATA_MODE", "invalid-mode");
    await expect(import("./env")).rejects.toThrow(/Invalid VITE_DATA_MODE/i);
  });
});
