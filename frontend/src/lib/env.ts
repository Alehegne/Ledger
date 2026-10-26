export type DataMode = "mock" | "api";

const resolveDataMode = (rawMode: string | undefined): DataMode => {
  const normalized = rawMode?.trim().toLowerCase();
  if (!normalized) {
    return "mock";
  }

  if (normalized === "mock" || normalized === "api") {
    return normalized;
  }

  throw new Error(
    `Invalid VITE_DATA_MODE value "${rawMode}". Supported values are "mock" and "api".`,
  );
};

const resolveApiBaseUrl = (rawUrl: string | undefined): string => {
  const normalized = rawUrl?.trim();
  return normalized || "http://localhost:8080";
};

export const env = {
  mode: resolveDataMode(import.meta.env.VITE_DATA_MODE),
  apiBaseUrl: resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL),
};
