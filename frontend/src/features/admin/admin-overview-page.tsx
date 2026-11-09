import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  Building2,
  CheckCircle2,
  FileSpreadsheet,
  History,
  ShieldAlert,
  Users,
} from "lucide-react";
import { Link } from "react-router-dom";
import { formatTimestamp } from "../../lib/date";
import { formatActionableError } from "../../lib/errors";
import { useLedgerService } from "../../services/interfaces/service-context";

export const AdminOverviewPage = () => {
  const service = useLedgerService();

  const overviewQuery = useQuery({
    queryKey: ["platformOverview"],
    queryFn: () => service.getPlatformOverview(),
  });

  const recentAuditQuery = useQuery({
    queryKey: ["platformAuditLogs", "overview-preview"],
    queryFn: () => service.listPlatformAuditLogs({ page: 1, size: 5 }),
  });

  if (overviewQuery.isLoading) {
    return (
      <div className="page-card p-8 text-center text-sm text-slate-500">
        Loading platform overview…
      </div>
    );
  }

  if (overviewQuery.isError) {
    const actionable = formatActionableError(
      overviewQuery.error,
      "Unable to load platform overview.",
    );
    return (
      <div className="feedback-error" role="alert">
        <p className="font-semibold">{actionable.title}</p>
        <p className="mt-1">{actionable.message}</p>
      </div>
    );
  }

  const overview = overviewQuery.data!;
  const hasUnbalancedTenants = overview.unbalancedTenantOrganizations > 0;

  return (
    <div className="space-y-6">
      <div className="page-card flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="page-header-kicker">Platform Operations</p>
          <h2 className="page-title">Customer Platform Health</h2>
          <p className="mt-1 text-sm text-slate-500">
            Aggregate operational posture across provisioned customer
            organizations. Customer accounting records remain isolated from
            platform administration.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to="/admin/organizations" className="btn-primary">
            <Building2 className="h-4 w-4" />
            Manage organizations
          </Link>
          <Link to="/admin/audit" className="btn-secondary">
            <History className="h-4 w-4" />
            Platform audit logs
          </Link>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <div className="page-card p-5">
          <div className="flex items-center justify-between text-xs uppercase tracking-[0.16em] text-slate-500">
            <span>Tenant Organizations</span>
            <Building2 className="h-4 w-4 text-slate-400" />
          </div>
          <p className="mt-2 text-3xl font-semibold tabular-nums text-slate-900">
            {overview.totalTenantOrganizations}
          </p>
          <p className="mt-2 text-xs text-slate-600">
            {overview.activeTenantOrganizations} active •{" "}
            {overview.pendingTenantOrganizations} pending •{" "}
            {overview.suspendedTenantOrganizations} suspended
          </p>
        </div>

        <div className="page-card p-5">
          <div className="flex items-center justify-between text-xs uppercase tracking-[0.16em] text-slate-500">
            <span>Tenant Users</span>
            <Users className="h-4 w-4 text-slate-400" />
          </div>
          <p className="mt-2 text-3xl font-semibold tabular-nums text-slate-900">
            {overview.totalTenantUsers}
          </p>
          <p className="mt-2 text-xs text-slate-600">
            {overview.disabledTenantUsers} disabled accounts
          </p>
        </div>

        <div className="page-card p-5">
          <div className="flex items-center justify-between text-xs uppercase tracking-[0.16em] text-slate-500">
            <span>Ledger Scale</span>
            <FileSpreadsheet className="h-4 w-4 text-slate-400" />
          </div>
          <p className="mt-2 text-3xl font-semibold tabular-nums text-slate-900">
            {overview.totalPostedJournalEntries}
          </p>
          <p className="mt-2 text-xs text-slate-600">
            Posted entries across {overview.totalTenantAccounts} accounts
          </p>
        </div>

        <div className="page-card p-5">
          <div className="flex items-center justify-between text-xs uppercase tracking-[0.16em] text-slate-500">
            <span>Trial Balance Integrity</span>
            {hasUnbalancedTenants ? (
              <AlertTriangle className="h-4 w-4 text-amber-600" />
            ) : (
              <CheckCircle2 className="h-4 w-4 text-emerald-600" />
            )}
          </div>
          <div className="mt-2 flex items-center gap-2">
            <span
              className={`status-badge ${
                hasUnbalancedTenants
                  ? "bg-amber-100 text-amber-800"
                  : "status-active"
              }`}
            >
              {hasUnbalancedTenants
                ? `${overview.unbalancedTenantOrganizations} Unbalanced`
                : "All Balanced"}
            </span>
          </div>
          <p className="mt-2 text-xs text-slate-500">
            Last platform action:{" "}
            {formatTimestamp(overview.lastPlatformActionAt)}
          </p>
        </div>
      </div>

      {overview.suspendedTenantOrganizations > 0 ||
      overview.pendingTenantOrganizations > 0 ? (
        <div className="page-card-muted flex items-start gap-3 p-4 text-sm text-slate-700">
          <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" />
          <div>
            <p className="font-medium text-slate-900">
              Lifecycle attention summary
            </p>
            <p className="mt-0.5 text-xs text-slate-600">
              {overview.pendingTenantOrganizations} organization(s) awaiting
              initial administrator onboarding acceptance and{" "}
              {overview.suspendedTenantOrganizations} suspended organization(s).
            </p>
          </div>
        </div>
      ) : null}

      <div className="page-card p-5">
        <div className="mb-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-slate-500" />
            <h3 className="text-base font-semibold text-slate-900">
              Recent Platform Governance Events
            </h3>
          </div>
          <Link
            to="/admin/audit"
            className="text-xs font-medium text-slate-700 underline hover:text-slate-900"
          >
            View all events
          </Link>
        </div>

        {recentAuditQuery.isLoading ? (
          <p className="text-sm text-slate-500">Loading recent events…</p>
        ) : (recentAuditQuery.data?.items ?? []).length === 0 ? (
          <p className="text-sm text-slate-500">
            No platform administration events recorded yet.
          </p>
        ) : (
          <div className="divide-y divide-slate-200">
            {(recentAuditQuery.data?.items ?? []).map((event) => (
              <div
                key={event.id}
                className="flex flex-col gap-1 py-3 sm:flex-row sm:items-center sm:justify-between"
              >
                <div>
                  <span className="inline-block rounded-full border border-slate-200 bg-slate-100 px-2.5 py-0.5 text-xs font-semibold text-slate-800">
                    {event.action}
                  </span>
                  <span className="ml-2 text-sm text-slate-800">
                    {event.targetOrganizationName ?? "Platform"}
                  </span>
                  {event.targetUserEmail ? (
                    <span className="ml-2 text-xs text-slate-500">
                      ({event.targetUserEmail})
                    </span>
                  ) : null}
                </div>
                <div className="text-xs text-slate-500">
                  {event.actor} • {formatTimestamp(event.createdAt)}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
