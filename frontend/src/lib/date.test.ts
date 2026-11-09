import { describe, expect, it } from "vitest";
import {
  formatDate,
  formatDateOnly,
  formatTimestamp,
  getReportingDateRange,
  parseIsoDateParts,
} from "./date";

describe("timezone-safe date helpers", () => {
  it("parses valid YYYY-MM-DD dates and extracts calendar parts without timezone shift", () => {
    expect(parseIsoDateParts("2026-11-04")).toEqual({
      year: 2026,
      month: 11,
      day: 4,
    });
    expect(parseIsoDateParts("2026-11-04T23:59:59Z")).toEqual({
      year: 2026,
      month: 11,
      day: 4,
    });
    expect(parseIsoDateParts("2026-02-30")).toBeNull();
    expect(parseIsoDateParts("not-a-date")).toBeNull();
  });

  it("formats pure ISO dates without shifting across UTC boundaries", () => {
    expect(formatDateOnly("2026-11-04")).toBe("Nov 4, 2026");
    expect(formatDateOnly("2026-01-01")).toBe("Jan 1, 2026");
    expect(formatDateOnly("2026-11-04T00:00:00Z")).toBe("Nov 4, 2026");
    expect(formatDateOnly("")).toBe("—");
    expect(formatDateOnly(null)).toBe("—");
  });

  it("formats timestamps and date strings safely", () => {
    expect(formatDate("2026-11-04")).toBe("Nov 4, 2026");
    expect(formatTimestamp("")).toBe("—");
    expect(formatTimestamp("invalid")).toBe("invalid");
  });

  it("computes deterministic reporting date ranges across months, quarters, and year boundaries", () => {
    expect(getReportingDateRange("CURRENT_MONTH", "2026-11-09")).toEqual({
      fromDate: "2026-11-01",
      toDate: "2026-11-30",
      asOfDate: "2026-11-09",
    });

    expect(getReportingDateRange("PREVIOUS_MONTH", "2026-01-15")).toEqual({
      fromDate: "2025-12-01",
      toDate: "2025-12-31",
      asOfDate: "2025-12-31",
    });

    expect(getReportingDateRange("PREVIOUS_MONTH", "2024-03-10")).toEqual({
      fromDate: "2024-02-01",
      toDate: "2024-02-29",
      asOfDate: "2024-02-29",
    });

    expect(getReportingDateRange("QUARTER_TO_DATE", "2026-11-09")).toEqual({
      fromDate: "2026-10-01",
      toDate: "2026-11-09",
      asOfDate: "2026-11-09",
    });

    expect(getReportingDateRange("YEAR_TO_DATE", "2026-11-09")).toEqual({
      fromDate: "2026-01-01",
      toDate: "2026-11-09",
      asOfDate: "2026-11-09",
    });

    expect(getReportingDateRange("ALL_TIME", "2026-11-09")).toEqual({
      fromDate: "",
      toDate: "",
      asOfDate: "2026-11-09",
    });
  });
});
