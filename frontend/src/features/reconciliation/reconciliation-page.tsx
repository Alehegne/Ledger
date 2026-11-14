import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Eye,
  Play,
  Scale,
} from "lucide-react";
import { useAuth } from "../../app/auth-context";
import { formatTimestamp } from "../../lib/date";
import { formatMoney } from "../../lib/money";
import { useLedgerService } from "../../services/interfaces/service-context";
import type { ReconciliationRun } from "../../types/domain";

export const ReconciliationPage = () => {
  const service = useLedgerService();
  const queryClient = useQueryClient();
  const { session } = useAuth();
  const currency = session?.user.baseCurrency ?? "USD";

  const [page, setPage] = useState(1);
  const pageSize = 10;
  const [selectedRun, setSelectedRun] = useState<ReconciliationRun | null>(null);

  const historyQuery = useQuery({
    queryKey: ["reconciliationHistory", page, pageSize],
    queryFn: () => service.listReconciliationHistory({ page, size: pageSize }),
  });

  const runMutation = useMutation({
    mutationFn: () => service.runReconciliation(),
    onSuccess: (createdRun) => {
      setSelectedRun(createdRun);
      setPage(1);
      queryClient.invalidateQueries({ queryKey: ["reconciliationHistory"] });
      queryClient.invalidateQueries({ queryKey: ["auditLogs"] });
    },
  });

  const activeRun =
    selectedRun ?? historyQuery.data?.items?.[0] ?? null;

  const isZeroVariance = (variance: string) => {
    const parsed = Number(variance);
    return Number.isFinite(parsed) && parsed === 0;
  };

  return (
    <div className="space-y-6">
      <div className="page-card flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-900 text-white">
            <Scale className="h-5 w-5" />
          </div>
          <div>
            <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
              Financial Integrity & Controls
            </p>
            <h2 className="text-2xl font-semibold text-slate-900">
              Ledger Reconciliation
            </h2>
          </div>
        </div>

        <button
          type="button"
          onClick={() => runMutation.mutate()}
          disabled={runMutation.isPending}
          className="btn-primary inline-flex items-center gap-2 self-start sm:self-auto"
        >
          <Play className="h-4 w-4" />
          {runMutation.isPending
            ? "Running Reconciliation…"
            : "Run Reconciliation"}
        </button>
      </div>

      {runMutation.isError ? (
        <div className="feedback-error" role="alert">
          {runMutation.error instanceof Error
            ? runMutation.error.message
            : "Failed to run financial reconciliation."}
        </div>
      ) : null}

      {historyQuery.isError ? (
        <div className="feedback-error" role="alert">
          {historyQuery.error instanceof Error
            ? historyQuery.error.message
            : "Failed to load reconciliation history."}
        </div>
      ) : null}

      {historyQuery.isLoading ? (
        <div className="page-card p-8 text-center text-sm text-slate-500">
          Loading reconciliation runs…
        </div>
      ) : activeRun ? (
        <div className="space-y-6">
          <section
            aria-label="Selected reconciliation run summary"
            className="page-card space-y-5 p-5"
          >
            <div className="flex flex-col gap-3 border-b border-slate-100 pb-4 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-3">
                {activeRun.status === "PASSED" ? (
                  <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-800">
                    <CheckCircle2 className="h-4 w-4" />
                    PASSED
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 rounded-full border border-red-200 bg-red-50 px-3 py-1 text-xs font-semibold text-red-800">
                    <AlertTriangle className="h-4 w-4" />
                    FAILED
                  </span>
                )}
                <div>
                  <h3 className="text-base font-semibold text-slate-900">
                    Reconciliation Run #{activeRun.id}
                  </h3>
                  <p className="text-xs text-slate-500">
                    Triggered {formatTimestamp(activeRun.createdAt)} by{" "}
                    {activeRun.triggeredByName ?? "System"}
                  </p>
                </div>
              </div>

              <div className="text-xs text-slate-600">
                {activeRun.checksPassed} passed / {activeRun.checksFailed} failed
              </div>
            </div>

            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <p className="text-xs uppercase tracking-[0.15em] text-slate-500">
                  Posted Debits
                </p>
                <p className="mt-1 font-mono text-lg font-semibold text-slate-900">
                  {formatMoney(activeRun.totalPostedDebits, currency)}
                </p>
              </div>
              <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <p className="text-xs uppercase tracking-[0.15em] text-slate-500">
                  Posted Credits
                </p>
                <p className="mt-1 font-mono text-lg font-semibold text-slate-900">
                  {formatMoney(activeRun.totalPostedCredits, currency)}
                </p>
              </div>
              <div
                className={`rounded-xl border p-4 ${
                  isZeroVariance(activeRun.globalVariance)
                    ? "border-emerald-200 bg-emerald-50/70"
                    : "border-red-200 bg-red-50/70"
                }`}
              >
                <p className="text-xs uppercase tracking-[0.15em] text-slate-500">
                  Global Variance
                </p>
                <p
                  className={`mt-1 font-mono text-lg font-semibold ${
                    isZeroVariance(activeRun.globalVariance)
                      ? "text-emerald-800"
                      : "text-red-800"
                  }`}
                >
                  {formatMoney(activeRun.globalVariance, currency)}
                </p>
              </div>
              <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <p className="text-xs uppercase tracking-[0.15em] text-slate-500">
                  Evaluated Scope
                </p>
                <p className="mt-1 text-sm font-semibold text-slate-900">
                  {activeRun.postedEntryCount} posted entries
                </p>
                <p className="text-xs text-slate-500">
                  {activeRun.accountCount} accounts
                </p>
              </div>
            </div>

            <div className="space-y-3">
              <h4 className="text-sm font-semibold text-slate-800">
                Integrity Verification Checks
              </h4>
              <div className="space-y-3">
                {activeRun.checks.map((check) => (
                  <div
                    key={check.code}
                    className={`rounded-xl border p-4 ${
                      check.passed
                        ? "border-slate-200 bg-white"
                        : "border-red-200 bg-red-50/40"
                    }`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        {check.passed ? (
                          <span className="inline-flex items-center gap-1 rounded-full border border-emerald-200 bg-emerald-50 px-2.5 py-0.5 text-xs font-semibold text-emerald-800">
                            <CheckCircle2 className="h-3.5 w-3.5" />
                            PASS
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 rounded-full border border-red-200 bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-800">
                            <AlertTriangle className="h-3.5 w-3.5" />
                            FAIL
                          </span>
                        )}
                        <span className="font-semibold text-slate-900">
                          {check.name}
                        </span>
                        <span className="font-mono text-xs text-slate-400">
                          {check.code}
                        </span>
                      </div>

                      <span className="font-mono text-xs text-slate-600">
                        Variance: {formatMoney(check.variance, currency)}
                      </span>
                    </div>

                    <p className="mt-2 text-sm text-slate-700">
                      {check.summary}
                    </p>

                    {check.discrepancies.length > 0 ? (
                      <div className="mt-3 overflow-x-auto rounded-lg border border-red-200 bg-white">
                        <table className="min-w-full text-left text-xs">
                          <thead className="bg-red-50 text-red-900">
                            <tr>
                              <th className="px-3 py-2 font-medium">Entity</th>
                              <th className="px-3 py-2 font-medium">
                                Reference
                              </th>
                              <th className="px-3 py-2 font-medium text-right">
                                Expected
                              </th>
                              <th className="px-3 py-2 font-medium text-right">
                                Actual
                              </th>
                              <th className="px-3 py-2 font-medium text-right">
                                Variance
                              </th>
                              <th className="px-3 py-2 font-medium">Detail</th>
                            </tr>
                          </thead>
                          <tbody>
                            {check.discrepancies.map((item, index) => (
                              <tr
                                key={`${item.entityType}-${item.entityId}-${index}`}
                                className="border-t border-red-100"
                              >
                                <td className="whitespace-nowrap px-3 py-2 font-mono text-slate-700">
                                  {item.entityType} #{item.entityId}
                                </td>
                                <td className="px-3 py-2 text-slate-700">
                                  {item.reference || "-"}
                                </td>
                                <td className="whitespace-nowrap px-3 py-2 text-right font-mono text-slate-700">
                                  {formatMoney(item.expectedAmount, currency)}
                                </td>
                                <td className="whitespace-nowrap px-3 py-2 text-right font-mono text-slate-700">
                                  {formatMoney(item.actualAmount, currency)}
                                </td>
                                <td className="whitespace-nowrap px-3 py-2 text-right font-mono font-semibold text-red-700">
                                  {formatMoney(item.variance, currency)}
                                </td>
                                <td className="px-3 py-2 text-slate-600">
                                  {item.detail}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section aria-label="Reconciliation history" className="space-y-3">
            <div className="flex items-center justify-between px-1">
              <h3 className="text-sm font-semibold uppercase tracking-[0.15em] text-slate-500">
                Run History
              </h3>
            </div>

            <div className="table-wrap">
              <table className="min-w-full text-left text-sm">
                <thead className="table-head">
                  <tr>
                    <th className="px-4 py-3 font-medium">Run</th>
                    <th className="px-4 py-3 font-medium">Timestamp</th>
                    <th className="px-4 py-3 font-medium">Triggered By</th>
                    <th className="px-4 py-3 font-medium">Status</th>
                    <th className="px-4 py-3 text-right font-medium">
                      Variance
                    </th>
                    <th className="px-4 py-3 font-medium">Checks</th>
                    <th className="px-4 py-3 text-right font-medium">
                      Details
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(historyQuery.data?.items ?? []).map((run) => {
                    const isSelected = activeRun?.id === run.id;
                    return (
                      <tr
                        key={run.id}
                        className={`border-t border-slate-200 ${
                          isSelected ? "bg-slate-50" : ""
                        }`}
                      >
                        <td className="px-4 py-3 font-mono text-xs font-semibold text-slate-800">
                          #{run.id}
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-xs text-slate-600">
                          {formatTimestamp(run.createdAt)}
                        </td>
                        <td className="px-4 py-3 text-slate-800">
                          {run.triggeredByName ?? "System"}
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`inline-block rounded-full border px-2.5 py-0.5 text-xs font-semibold ${
                              run.status === "PASSED"
                                ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                                : "border-red-200 bg-red-50 text-red-800"
                            }`}
                          >
                            {run.status}
                          </span>
                        </td>
                        <td className="whitespace-nowrap px-4 py-3 text-right font-mono text-xs text-slate-700">
                          {formatMoney(run.globalVariance, currency)}
                        </td>
                        <td className="px-4 py-3 text-xs text-slate-600">
                          {run.checksPassed} pass / {run.checksFailed} fail
                        </td>
                        <td className="px-4 py-3 text-right">
                          <button
                            type="button"
                            onClick={() => setSelectedRun(run)}
                            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100"
                          >
                            <Eye className="h-3.5 w-3.5" />
                            Inspect
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="flex items-center justify-between px-2 text-xs text-slate-500">
              <div>
                Total: {historyQuery.data?.totalElements ?? 0} runs | Page{" "}
                {historyQuery.data?.page ?? 1} of{" "}
                {historyQuery.data?.totalPages ?? 1}
              </div>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={(historyQuery.data?.page ?? 1) <= 1}
                  onClick={() => setPage((p) => Math.max(1, p - 1))}
                  className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-slate-700 disabled:opacity-40"
                >
                  <ChevronLeft className="h-3.5 w-3.5" />
                  Previous
                </button>
                <button
                  type="button"
                  disabled={
                    (historyQuery.data?.page ?? 1) >=
                    (historyQuery.data?.totalPages ?? 1)
                  }
                  onClick={() => setPage((p) => p + 1)}
                  className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-slate-700 disabled:opacity-40"
                >
                  Next
                  <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          </section>
        </div>
      ) : (
        <div className="page-card p-8 text-center">
          <p className="text-sm font-medium text-slate-800">
            No reconciliation runs recorded yet.
          </p>
          <p className="mt-1 text-xs text-slate-500">
            Trigger a reconciliation run to verify global double-entry balance,
            per-entry integrity, trial balance tie-out, and account hierarchy
            structure.
          </p>
        </div>
      )}
    </div>
  );
};
