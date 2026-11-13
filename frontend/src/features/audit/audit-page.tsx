import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Calendar,
  ChevronLeft,
  ChevronRight,
  Filter,
  History,
  Info,
  X,
} from "lucide-react";
import { formatTimestamp } from "../../lib/date";
import { useLedgerService } from "../../services/interfaces/service-context";
import type { AuditLogItem } from "../../types/domain";

const ACTION_COLORS: Record<string, string> = {
  ACCOUNT_CREATED: "bg-emerald-100 text-emerald-800 border-emerald-200",
  ACCOUNT_UPDATED: "bg-blue-100 text-blue-800 border-blue-200",
  ACCOUNT_DEACTIVATED: "bg-slate-100 text-slate-800 border-slate-200",
  JOURNAL_CREATED: "bg-sky-100 text-sky-800 border-sky-200",
  JOURNAL_UPDATED: "bg-indigo-100 text-indigo-800 border-indigo-200",
  JOURNAL_POSTED: "bg-emerald-100 text-emerald-800 border-emerald-200",
  JOURNAL_REVERSED: "bg-amber-100 text-amber-800 border-amber-200",
  USER_INVITED: "bg-purple-100 text-purple-800 border-purple-200",
  INVITATION_REVOKED: "bg-red-100 text-red-800 border-red-200",
  INVITATION_ACCEPTED: "bg-emerald-100 text-emerald-800 border-emerald-200",
  USER_ROLE_UPDATED: "bg-violet-100 text-violet-800 border-violet-200",
  ORGANIZATION_SETTINGS_UPDATED: "bg-teal-100 text-teal-800 border-teal-200",
  ORGANIZATION_ACTIVATED: "bg-emerald-100 text-emerald-800 border-emerald-200",
  ORGANIZATION_REGISTERED: "bg-emerald-100 text-emerald-800 border-emerald-200",
  RECONCILIATION_RUN: "bg-cyan-100 text-cyan-800 border-cyan-200",
};

export const AuditPage = () => {
  const service = useLedgerService();
  const [page, setPage] = useState(1);
  const pageSize = 15;
  const [actionFilter, setActionFilter] = useState<string>("");
  const [fromDate, setFromDate] = useState<string>("");
  const [toDate, setToDate] = useState<string>("");
  const [selectedLog, setSelectedLog] = useState<AuditLogItem | null>(null);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["auditLogs", page, pageSize, actionFilter, fromDate, toDate],
    queryFn: () =>
      service.listAuditLogs({
        page,
        size: pageSize,
        action: actionFilter || undefined,
        fromDate: fromDate || undefined,
        toDate: toDate || undefined,
      }),
  });

  const parseMetadata = (raw: string) => {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  };

  return (
    <div className="space-y-6">
      <div className="page-card flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-900 text-white">
            <History className="h-5 w-5" />
          </div>
          <div>
            <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
              Compliance & Governance
            </p>
            <h2 className="text-2xl font-semibold text-slate-900">
              System Audit Trail
            </h2>
          </div>
        </div>
      </div>

      <div className="page-card p-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2 text-xs font-semibold text-slate-600">
            <Filter className="h-4 w-4" />
            Filters:
          </div>

          <select
            aria-label="Filter by action"
            className="rounded-xl border border-slate-300 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 outline-none focus:border-slate-500"
            value={actionFilter}
            onChange={(e) => {
              setActionFilter(e.target.value);
              setPage(1);
            }}
          >
            <option value="">All Actions</option>
            <option value="ACCOUNT_CREATED">Account Created</option>
            <option value="ACCOUNT_UPDATED">Account Updated</option>
            <option value="ACCOUNT_DEACTIVATED">Account Deactivated</option>
            <option value="JOURNAL_CREATED">Journal Created</option>
            <option value="JOURNAL_POSTED">Journal Posted</option>
            <option value="JOURNAL_REVERSED">Journal Reversed</option>
            <option value="USER_INVITED">User Invited</option>
            <option value="INVITATION_REVOKED">Invitation Revoked</option>
            <option value="INVITATION_ACCEPTED">Invitation Accepted</option>
            <option value="USER_ROLE_UPDATED">User Role Updated</option>
            <option value="ORGANIZATION_SETTINGS_UPDATED">
              Org Settings Updated
            </option>
            <option value="ORGANIZATION_ACTIVATED">Org Activated</option>
            <option value="ORGANIZATION_REGISTERED">Org Registered</option>
            <option value="RECONCILIATION_RUN">Reconciliation Run</option>
          </select>

          <div className="flex items-center gap-1.5 text-xs text-slate-600">
            <Calendar className="h-3.5 w-3.5 text-slate-400" />
            <span>From:</span>
            <input
              type="date"
              aria-label="From date"
              className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700 outline-none focus:border-slate-500"
              value={fromDate}
              onChange={(e) => {
                setFromDate(e.target.value);
                setPage(1);
              }}
            />
          </div>

          <div className="flex items-center gap-1.5 text-xs text-slate-600">
            <span>To:</span>
            <input
              type="date"
              aria-label="To date"
              className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700 outline-none focus:border-slate-500"
              value={toDate}
              onChange={(e) => {
                setToDate(e.target.value);
                setPage(1);
              }}
            />
          </div>

          {(actionFilter || fromDate || toDate) && (
            <button
              type="button"
              onClick={() => {
                setActionFilter("");
                setFromDate("");
                setToDate("");
                setPage(1);
              }}
              className="rounded-lg px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-slate-800"
            >
              Clear filters
            </button>
          )}
        </div>
      </div>

      {isLoading ? (
        <div className="page-card p-8 text-center text-sm text-slate-500">
          Loading audit logs…
        </div>
      ) : isError ? (
        <div className="feedback-error">
          {error instanceof Error ? error.message : "Failed to load audit logs."}
        </div>
      ) : (
        <div className="space-y-4">
          <div className="table-wrap">
            <table className="min-w-full text-left text-sm">
              <thead className="table-head">
                <tr>
                  <th className="px-4 py-3 font-medium">Timestamp</th>
                  <th className="px-4 py-3 font-medium">Actor</th>
                  <th className="px-4 py-3 font-medium">Action</th>
                  <th className="px-4 py-3 font-medium">Entity</th>
                  <th className="px-4 py-3 font-medium">Entity ID</th>
                  <th className="px-4 py-3 text-right font-medium">Details</th>
                </tr>
              </thead>
              <tbody>
                {(data?.items ?? []).length === 0 ? (
                  <tr>
                    <td
                      colSpan={6}
                      className="px-4 py-8 text-center text-slate-500"
                    >
                      No audit events matching criteria.
                    </td>
                  </tr>
                ) : (
                  (data?.items ?? []).map((log) => {
                    const badgeClass =
                      ACTION_COLORS[log.action] ??
                      "bg-slate-100 text-slate-700 border-slate-200";

                    return (
                      <tr key={log.id} className="border-t border-slate-200">
                        <td className="whitespace-nowrap px-4 py-3 text-xs text-slate-600">
                          {formatTimestamp(log.createdAt)}
                        </td>
                        <td className="px-4 py-3 font-medium text-slate-800">
                          {log.actor}
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`inline-block rounded-full border px-2.5 py-0.5 text-xs font-semibold ${badgeClass}`}
                          >
                            {log.action}
                          </span>
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-slate-600">
                          {log.entityType}
                        </td>
                        <td className="px-4 py-3 font-mono text-xs text-slate-500">
                          {log.entityId}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <button
                            type="button"
                            onClick={() => setSelectedLog(log)}
                            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
                          >
                            <Info className="h-3.5 w-3.5" />
                            Metadata
                          </button>
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination bar */}
          <div className="flex items-center justify-between px-2 text-xs text-slate-500">
            <div>
              Total: {data?.totalElements ?? 0} events | Page {data?.page ?? 1} of{" "}
              {data?.totalPages ?? 1}
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={(data?.page ?? 1) <= 1}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-slate-700 disabled:opacity-40"
              >
                <ChevronLeft className="h-3.5 w-3.5" />
                Previous
              </button>
              <button
                type="button"
                disabled={(data?.page ?? 1) >= (data?.totalPages ?? 1)}
                onClick={() => setPage((p) => p + 1)}
                className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-slate-700 disabled:opacity-40"
              >
                Next
                <ChevronRight className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Metadata Detail Modal */}
      {selectedLog ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm">
          <div className="w-full max-w-xl rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
              <div>
                <h3 className="text-lg font-semibold text-slate-900">
                  Audit Log #{selectedLog.id}
                </h3>
                <p className="text-xs text-slate-500">
                  {selectedLog.action} on {selectedLog.entityType}:
                  {selectedLog.entityId}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSelectedLog(null)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="mt-4 space-y-3">
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div>
                  <span className="font-semibold text-slate-500">Actor:</span>{" "}
                  <span className="text-slate-800">{selectedLog.actor}</span>
                </div>
                <div>
                  <span className="font-semibold text-slate-500">Timestamp:</span>{" "}
                  <span className="text-slate-800">
                    {formatTimestamp(selectedLog.createdAt)}
                  </span>
                </div>
              </div>

              <div>
                <p className="mb-1 text-xs font-semibold text-slate-500">
                  Metadata JSON
                </p>
                <pre className="max-h-60 overflow-auto rounded-xl bg-slate-900 p-3 font-mono text-xs text-slate-200">
                  {parseMetadata(selectedLog.metadata)}
                </pre>
              </div>
            </div>

            <div className="mt-5 flex justify-end">
              <button
                type="button"
                onClick={() => setSelectedLog(null)}
                className="btn-secondary text-xs"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
};
