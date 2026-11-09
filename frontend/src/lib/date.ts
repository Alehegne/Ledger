const DATE_ONLY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

const pad2 = (value: number): string => String(value).padStart(2, "0");

const utcDateFormatter = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeZone: "UTC",
});

const timestampFormatter = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

export interface IsoDateParts {
  year: number;
  month: number;
  day: number;
}

export const parseIsoDateParts = (isoDate: string): IsoDateParts | null => {
  const datePart = isoDate.trim().split("T")[0];
  const match = DATE_ONLY_PATTERN.exec(datePart);
  if (!match) {
    return null;
  }

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);

  if (month < 1 || month > 12 || day < 1 || day > 31) {
    return null;
  }

  const probe = new Date(Date.UTC(year, month - 1, day));
  if (
    probe.getUTCFullYear() !== year ||
    probe.getUTCMonth() !== month - 1 ||
    probe.getUTCDate() !== day
  ) {
    return null;
  }

  return { year, month, day };
};

export const formatDateOnly = (
  isoDate: string | null | undefined,
): string => {
  if (!isoDate || isoDate.trim().length === 0) {
    return "—";
  }

  const parts = parseIsoDateParts(isoDate);
  if (!parts) {
    return isoDate;
  }

  return utcDateFormatter.format(
    new Date(Date.UTC(parts.year, parts.month - 1, parts.day)),
  );
};

export const formatDate = (isoDate: string | null | undefined): string => {
  if (!isoDate || isoDate.trim().length === 0) {
    return "—";
  }

  if (DATE_ONLY_PATTERN.test(isoDate.trim())) {
    return formatDateOnly(isoDate);
  }

  const parsed = new Date(isoDate);
  if (Number.isNaN(parsed.getTime())) {
    return formatDateOnly(isoDate);
  }

  return new Intl.DateTimeFormat("en-US", { dateStyle: "medium" }).format(
    parsed,
  );
};

export const formatTimestamp = (
  isoTimestamp: string | null | undefined,
): string => {
  if (!isoTimestamp || isoTimestamp.trim().length === 0) {
    return "—";
  }

  const parsed = new Date(isoTimestamp);
  if (Number.isNaN(parsed.getTime())) {
    return isoTimestamp;
  }

  return timestampFormatter.format(parsed);
};

export const todayISO = (): string => {
  const now = new Date();
  return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
};

export type ReportingRangePreset =
  | "CURRENT_MONTH"
  | "PREVIOUS_MONTH"
  | "QUARTER_TO_DATE"
  | "YEAR_TO_DATE"
  | "ALL_TIME"
  | "CUSTOM";

export type FixedReportingRangePreset = Exclude<ReportingRangePreset, "CUSTOM">;

const formatIsoParts = ({ year, month, day }: IsoDateParts): string =>
  `${String(year).padStart(4, "0")}-${pad2(month)}-${pad2(day)}`;

const daysInMonthUtc = (year: number, month: number): number =>
  new Date(Date.UTC(year, month, 0)).getUTCDate();

export const getReportingDateRange = (
  preset: FixedReportingRangePreset,
  referenceDateIso = todayISO(),
): { fromDate: string; toDate: string; asOfDate: string } => {
  const parts = parseIsoDateParts(referenceDateIso) ??
    parseIsoDateParts(todayISO()) ?? {
      year: 2026,
      month: 1,
      day: 1,
    };

  const referenceIso = formatIsoParts(parts);

  switch (preset) {
    case "CURRENT_MONTH": {
      const lastDay = daysInMonthUtc(parts.year, parts.month);
      return {
        fromDate: formatIsoParts({ year: parts.year, month: parts.month, day: 1 }),
        toDate: formatIsoParts({
          year: parts.year,
          month: parts.month,
          day: lastDay,
        }),
        asOfDate: referenceIso,
      };
    }

    case "PREVIOUS_MONTH": {
      const prevMonth = parts.month === 1 ? 12 : parts.month - 1;
      const prevYear = parts.month === 1 ? parts.year - 1 : parts.year;
      const lastDay = daysInMonthUtc(prevYear, prevMonth);
      const prevEnd = formatIsoParts({
        year: prevYear,
        month: prevMonth,
        day: lastDay,
      });
      return {
        fromDate: formatIsoParts({ year: prevYear, month: prevMonth, day: 1 }),
        toDate: prevEnd,
        asOfDate: prevEnd,
      };
    }

    case "QUARTER_TO_DATE": {
      const quarterStartMonth = Math.floor((parts.month - 1) / 3) * 3 + 1;
      return {
        fromDate: formatIsoParts({
          year: parts.year,
          month: quarterStartMonth,
          day: 1,
        }),
        toDate: referenceIso,
        asOfDate: referenceIso,
      };
    }

    case "YEAR_TO_DATE": {
      return {
        fromDate: formatIsoParts({ year: parts.year, month: 1, day: 1 }),
        toDate: referenceIso,
        asOfDate: referenceIso,
      };
    }

    case "ALL_TIME": {
      return {
        fromDate: "",
        toDate: "",
        asOfDate: referenceIso,
      };
    }
  }
};
