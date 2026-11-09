import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Decimal from "decimal.js";
import { ArrowRightLeft, CalendarRange, ReceiptText, Scale } from "lucide-react";
import { useAuth } from "../../app/auth-context";
import {
  type FixedReportingRangePreset,
  type ReportingRangePreset,
  formatDateOnly,
  getReportingDateRange,
} from "../../lib/date";
import { getErrorMessage } from "../../lib/errors";
import { formatMoney } from "../../lib/money";
import { useLedgerService } from "../../services/interfaces/service-context";

const RANGE_PRESETS: ReadonlyArray<{
  id: FixedReportingRangePreset;
  label: string;
}> = [
  { id: "CURRENT_MONTH", label: "Current Month" },
  { id: "PREVIOUS_MONTH", label: "Previous Month" },
  { id: "QUARTER_TO_DATE", label: "Quarter to Date" },
  { id: "YEAR_TO_DATE", label: "Year to Date" },
  { id: "ALL_TIME", label: "All Time" },
];

export const ReportsPage = () => {
  const service = useLedgerService();
  const { session } = useAuth();
  const currency = session?.user.baseCurrency ?? "USD";
  const initialRange = useMemo(
    () => getReportingDateRange("CURRENT_MONTH"),
    [],
  );

  const [activePreset, setActivePreset] =
    useState<ReportingRangePreset>("CURRENT_MONTH");
  const [asOfDate, setAsOfDate] = useState(initialRange.asOfDate);
  const [accountId, setAccountId] = useState<number>(5);
  const [fromDate, setFromDate] = useState(initialRange.fromDate);
  const [toDate, setToDate] = useState(initialRange.toDate);
  const [ledgerPage, setLedgerPage] = useState(1);
  const ledgerPageSize = 5;

  const isInvalidDateRange = Boolean(fromDate && toDate && fromDate > toDate);

  const applyPreset = (preset: FixedReportingRangePreset) => {
    const nextRange = getReportingDateRange(preset);
    setActivePreset(preset);
    setFromDate(nextRange.fromDate);
    setToDate(nextRange.toDate);
    setAsOfDate(nextRange.asOfDate);
    setLedgerPage(1);
  };

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: () => service.listAccounts(),
  });

  const selectedAccountId = useMemo(() => {
    if (accounts.some((account) => account.id === accountId)) {
      return accountId;
    }
    return accounts[0]?.id ?? 1;
  }, [accountId, accounts]);

  const {
    data: trialBalance,
    isLoading: trialLoading,
    isError: trialError,
    error: trialErrorMessage,
  } = useQuery({
    queryKey: ["trialBalance", asOfDate],
    queryFn: () => service.getTrialBalance(asOfDate || undefined),
  });

  const {
    data: ledger,
    isLoading: ledgerLoading,
    isError: ledgerError,
    error: ledgerErrorMessage,
  } = useQuery({
    queryKey: [
      "generalLedger",
      selectedAccountId,
      fromDate,
      toDate,
      ledgerPage,
    ],
    queryFn: () =>
      service.getGeneralLedger({
        accountId: selectedAccountId,
        fromDate: fromDate || undefined,
        toDate: toDate || undefined,
        page: ledgerPage,
        size: ledgerPageSize,
      }),
    enabled: accounts.length > 0 && !isInvalidDateRange,
  });

  const trialDifference = trialBalance
    ? new Decimal(trialBalance.totalDebits).minus(trialBalance.totalCredits)
    : new Decimal(0);
  const totalLedgerPages = Math.max(1, ledger?.totalPages ?? 1);
  const reportsErrorMessage = trialError
    ? getErrorMessage(trialErrorMessage, "The reports service is unavailable.")
    : ledgerError
      ? getErrorMessage(ledgerErrorMessage, "The reports service is unavailable.")
      : null;

  return (
    <div className="space-y-6">
      <section className="page-card space-y-4 p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.15em] text-slate-500">
            <CalendarRange className="h-4 w-4 text-slate-600" />
            <span>Reporting period</span>
          </div>

          <div
            className="flex flex-wrap items-center gap-2"
            role="group"
            aria-label="Reporting date presets"
          >
            {RANGE_PRESETS.map((preset) => {
              const isSelected = activePreset === preset.id;
              return (
                <button
                  key={preset.id}
                  type="button"
                  onClick={() => applyPreset(preset.id)}
                  aria-pressed={isSelected}
                  className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition ${
                    isSelected
                      ? "border-slate-900 bg-slate-900 text-white"
                      : "border-slate-200 bg-white text-slate-700 hover:bg-slate-50"
                  }`}
                >
                  {preset.label}
                </button>
              );
            })}
            {activePreset === "CUSTOM" ? (
              <span className="rounded-lg bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                Custom Range
              </span>
            ) : null}
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-4">
          <label className="space-y-1 text-sm text-slate-700">
            <span>Trial balance as of</span>
            <input
              type="date"
              value={asOfDate}
              onChange={(event) => {
                setAsOfDate(event.target.value);
                setActivePreset("CUSTOM");
              }}
              className="input-control"
            />
          </label>

          <label className="space-y-1 text-sm text-slate-700">
            <span>Ledger account</span>
            <select
              value={selectedAccountId}
              onChange={(event) => {
                setAccountId(Number(event.target.value));
                setLedgerPage(1);
              }}
              className="input-control"
            >
              {accounts.map((account) => (
                <option key={account.id} value={account.id}>
                  {account.accountCode} - {account.name}
                </option>
              ))}
            </select>
          </label>

          <label className="space-y-1 text-sm text-slate-700">
            <span>From date</span>
            <input
              type="date"
              value={fromDate}
              onChange={(event) => {
                setFromDate(event.target.value);
                setActivePreset("CUSTOM");
                setLedgerPage(1);
              }}
              className="input-control"
            />
          </label>

          <label className="space-y-1 text-sm text-slate-700">
            <span>To date</span>
            <input
              type="date"
              value={toDate}
              onChange={(event) => {
                setToDate(event.target.value);
                setActivePreset("CUSTOM");
                setLedgerPage(1);
              }}
              className="input-control"
            />
          </label>
        </div>

        {isInvalidDateRange ? (
          <div className="feedback-error" role="alert">
            Start date must be on or before end date.
          </div>
        ) : null}
      </section>

      {trialLoading || (ledgerLoading && !isInvalidDateRange) ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-500">
          Loading reports…
        </div>
      ) : reportsErrorMessage || !trialBalance || (!ledger && !isInvalidDateRange) ? (
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-sm text-red-700">
          Reports unavailable. {reportsErrorMessage ?? "The reports service is unavailable."}
        </div>
      ) : (
        <>
          <section className="grid gap-6 xl:grid-cols-2">
            <div className="page-card p-5">
              <div className="mb-4 flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Scale className="h-4 w-4 text-slate-700" />
                  <h2 className="text-lg font-semibold text-slate-900">
                    Trial balance
                  </h2>
                </div>
                <span className="text-xs text-slate-500">
                  As of {formatDateOnly(trialBalance.asOfDate)}
                </span>
              </div>

              <div className="grid gap-3 sm:grid-cols-2">
                <div className="page-card-muted p-3">
                  <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                    Debits
                  </p>
                  <p className="mt-2 text-xl font-semibold text-slate-900">
                    {formatMoney(trialBalance.totalDebits, currency)}
                  </p>
                </div>
                <div className="page-card-muted p-3">
                  <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                    Credits
                  </p>
                  <p className="mt-2 text-xl font-semibold text-slate-900">
                    {formatMoney(trialBalance.totalCredits, currency)}
                  </p>
                </div>
              </div>

              <div className="mt-4 overflow-x-auto rounded-xl border border-slate-200">
                <table className="min-w-full text-left text-sm">
                  <thead className="table-head">
                    <tr>
                      <th className="px-3 py-2 font-medium">Code</th>
                      <th className="px-3 py-2 font-medium">Account</th>
                      <th className="px-3 py-2 text-right font-medium">Debit</th>
                      <th className="px-3 py-2 text-right font-medium">Credit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {trialBalance.accounts.map((account) => (
                      <tr
                        key={account.accountId}
                        className="border-t border-slate-200"
                      >
                        <td className="px-3 py-2 text-slate-600">
                          {account.accountCode}
                        </td>
                        <td className="px-3 py-2 text-slate-700">
                          {account.accountName}
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">
                          {formatMoney(account.debitBalance, currency)}
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">
                          {formatMoney(account.creditBalance, currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot className="border-t border-slate-200 bg-slate-50 text-slate-800">
                    <tr>
                      <td className="px-3 py-2 font-semibold" colSpan={2}>
                        Totals
                      </td>
                      <td className="px-3 py-2 text-right font-semibold tabular-nums">
                        {formatMoney(trialBalance.totalDebits, currency)}
                      </td>
                      <td className="px-3 py-2 text-right font-semibold tabular-nums">
                        {formatMoney(trialBalance.totalCredits, currency)}
                      </td>
                    </tr>
                  </tfoot>
                </table>
              </div>

              <p className="mt-3 text-sm text-slate-600">
                Difference:{" "}
                {formatMoney(trialDifference.abs().toFixed(2), currency)}
              </p>
            </div>

            <div className="page-card p-5">
              <div className="mb-4 flex items-center gap-2">
                <ReceiptText className="h-4 w-4 text-slate-700" />
                <h2 className="text-lg font-semibold text-slate-900">
                  General ledger
                </h2>
              </div>

              {!ledger ? (
                <div className="rounded-xl border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                  Adjust date filters to load general ledger activity.
                </div>
              ) : (
                <>
                  <div className="page-card-muted p-3">
                    <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                      Account
                    </p>
                    <p className="mt-2 text-base font-semibold text-slate-900">
                      {ledger.accountName}
                    </p>
                    <p className="mt-1 text-sm text-slate-600">
                      Opening: {formatMoney(ledger.openingBalance, currency)} ·
                      Closing: {formatMoney(ledger.closingBalance, currency)}
                    </p>
                  </div>

                  {ledger.lines.length === 0 ? (
                    <div className="mt-4 rounded-xl border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                      No posted transactions for this account in the selected
                      date range.
                    </div>
                  ) : (
                    <div className="mt-4 overflow-x-auto rounded-xl border border-slate-200">
                      <table className="min-w-full text-left text-sm">
                        <thead className="table-head">
                          <tr>
                            <th className="px-3 py-2 font-medium">Date</th>
                            <th className="px-3 py-2 font-medium">
                              Description
                            </th>
                            <th className="px-3 py-2 text-right font-medium">
                              Debit
                            </th>
                            <th className="px-3 py-2 text-right font-medium">
                              Credit
                            </th>
                            <th className="px-3 py-2 text-right font-medium">
                              Running
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {ledger.lines.map((line) => (
                            <tr
                              key={`${line.journalEntryId}-${line.side}-${line.amount}`}
                              className="border-t border-slate-200"
                            >
                              <td className="px-3 py-2 text-slate-700">
                                {formatDateOnly(line.transactionDate)}
                              </td>
                              <td className="px-3 py-2 text-slate-700">
                                {line.description}
                              </td>
                              <td className="px-3 py-2 text-right tabular-nums text-slate-900">
                                {line.side === "DEBIT"
                                  ? formatMoney(line.amount, currency)
                                  : "—"}
                              </td>
                              <td className="px-3 py-2 text-right tabular-nums text-slate-900">
                                {line.side === "CREDIT"
                                  ? formatMoney(line.amount, currency)
                                  : "—"}
                              </td>
                              <td className="px-3 py-2 text-right tabular-nums text-slate-900">
                                {formatMoney(line.runningBalance, currency)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}

                  <div className="mt-3 flex items-center justify-between text-sm text-slate-700">
                    <span>
                      Page {ledger.page} of {totalLedgerPages}
                    </span>
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={() =>
                          setLedgerPage((value) => Math.max(1, value - 1))
                        }
                        disabled={ledger.page <= 1}
                        className="btn-secondary"
                      >
                        Previous
                      </button>
                      <button
                        type="button"
                        onClick={() =>
                          setLedgerPage((value) =>
                            Math.min(totalLedgerPages, value + 1),
                          )
                        }
                        disabled={ledger.page >= totalLedgerPages}
                        className="btn-secondary"
                      >
                        Next
                      </button>
                    </div>
                  </div>
                </>
              )}
            </div>
          </section>

          <section className="page-card p-5">
            <div className="mb-4 flex items-center gap-2">
              <ArrowRightLeft className="h-4 w-4 text-slate-700" />
              <h2 className="text-lg font-semibold text-slate-900">
                Balance status
              </h2>
            </div>

            <div className="flex items-center justify-between rounded-xl bg-emerald-50 px-4 py-3">
              <div>
                <p className="text-xs uppercase tracking-[0.2em] text-emerald-700">
                  Status
                </p>
                <p className="text-sm font-medium text-emerald-900">
                  {trialBalance.balanced
                    ? "Balanced and ready for review"
                    : "Unbalanced entry flagged"}
                </p>
              </div>
              <span
                className={`inline-flex rounded-full px-3 py-1 text-xs font-medium ${trialBalance.balanced ? "bg-emerald-100 text-emerald-700" : "bg-red-100 text-red-700"}`}
              >
                {trialBalance.balanced ? "Balanced" : "Attention"}
              </span>
            </div>
          </section>
        </>
      )}
    </div>
  );
};
