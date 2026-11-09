import { type FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Building2,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  KeyRound,
  Plus,
  Search,
  ShieldAlert,
  ShieldCheck,
  UserCheck,
  UserX,
  X,
} from "lucide-react";
import { useToast } from "../../app/toast-store";
import { formatDate, formatTimestamp } from "../../lib/date";
import { formatActionableError } from "../../lib/errors";
import { useLedgerService } from "../../services/interfaces/service-context";
import type {
  AdminCreateOrganizationResult,
  OrganizationStatus,
  RecoveryInvitationResult,
  UserStatus,
} from "../../types/domain";

const ORG_STATUS_BADGE: Record<OrganizationStatus, string> = {
  ACTIVE: "status-active",
  PENDING: "status-draft",
  SUSPENDED: "bg-red-100 text-red-700",
};

export const AdminOrganizationsPage = () => {
  const service = useLedgerService();
  const queryClient = useQueryClient();
  const { showSuccess } = useToast();

  const [page, setPage] = useState(1);
  const pageSize = 15;
  const [searchInput, setSearchInput] = useState("");
  const [statusFilter, setStatusFilter] = useState<OrganizationStatus | "">("");

  const [provisionOpen, setProvisionOpen] = useState(false);
  const [orgNameInput, setOrgNameInput] = useState("");
  const [baseCurrencyInput, setBaseCurrencyInput] = useState("USD");
  const [adminEmailInput, setAdminEmailInput] = useState("");
  const [provisionError, setProvisionError] = useState<string | null>(null);
  const [provisionResult, setProvisionResult] =
    useState<AdminCreateOrganizationResult | null>(null);

  const [selectedOrgId, setSelectedOrgId] = useState<number | null>(null);
  const [editOverrides, setEditOverrides] = useState<
    Partial<{ name: string; baseCurrency: string }>
  >({});
  const [suspendReason, setSuspendReason] = useState("");
  const [reactivateNote, setReactivateNote] = useState("");
  const [recoveryEmail, setRecoveryEmail] = useState("");
  const [recoveryResult, setRecoveryResult] =
    useState<RecoveryInvitationResult | null>(null);
  const [detailError, setDetailError] = useState<{
    title: string;
    message: string;
  } | null>(null);

  const organizationsQuery = useQuery({
    queryKey: [
      "platformOrganizations",
      page,
      pageSize,
      searchInput,
      statusFilter,
    ],
    queryFn: () =>
      service.listPlatformOrganizations({
        page,
        size: pageSize,
        search: searchInput.trim() || undefined,
        status: statusFilter || undefined,
      }),
  });

  const detailQuery = useQuery({
    queryKey: ["platformOrganizationDetail", selectedOrgId],
    queryFn: () => service.getPlatformOrganizationDetail(selectedOrgId!),
    enabled: selectedOrgId !== null,
  });

  const orgUsersQuery = useQuery({
    queryKey: ["platformOrganizationUsers", selectedOrgId],
    queryFn: () => service.listPlatformOrganizationUsers(selectedOrgId!),
    enabled: selectedOrgId !== null,
  });

  const invalidatePlatformQueries = async (orgId?: number) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["platformOrganizations"] }),
      queryClient.invalidateQueries({ queryKey: ["platformOverview"] }),
      queryClient.invalidateQueries({ queryKey: ["platformAuditLogs"] }),
      ...(orgId !== undefined
        ? [
            queryClient.invalidateQueries({
              queryKey: ["platformOrganizationDetail", orgId],
            }),
            queryClient.invalidateQueries({
              queryKey: ["platformOrganizationUsers", orgId],
            }),
          ]
        : []),
    ]);
  };

  const provisionMutation = useMutation({
    mutationFn: (payload: {
      organizationName: string;
      baseCurrency: string;
      adminEmail: string;
    }) => service.createPlatformOrganization(payload),
    onSuccess: async (result) => {
      setProvisionError(null);
      setProvisionResult(result);
      showSuccess(`Provisioned organization ${result.organizationName}.`);
      await invalidatePlatformQueries();
    },
    onError: (err) => {
      setProvisionError(
        formatActionableError(err, "Failed to provision organization.")
          .message,
      );
    },
  });

  const updateOrgMutation = useMutation({
    mutationFn: (payload: {
      orgId: number;
      name?: string;
      baseCurrency?: string;
    }) =>
      service.updatePlatformOrganization(payload.orgId, {
        name: payload.name,
        baseCurrency: payload.baseCurrency,
      }),
    onSuccess: async (updated) => {
      setDetailError(null);
      setEditOverrides({});
      showSuccess(`Updated ${updated.name}.`);
      await invalidatePlatformQueries(updated.id);
    },
    onError: (err) => {
      const actionable = formatActionableError(
        err,
        "Unable to update organization.",
      );
      setDetailError({ title: actionable.title, message: actionable.message });
    },
  });

  const updateOrgStatusMutation = useMutation({
    mutationFn: (payload: {
      orgId: number;
      status: "ACTIVE" | "SUSPENDED";
      reason?: string;
    }) =>
      service.updatePlatformOrganizationStatus(payload.orgId, {
        status: payload.status,
        reason: payload.reason,
      }),
    onSuccess: async (updated) => {
      setDetailError(null);
      setSuspendReason("");
      setReactivateNote("");
      showSuccess(
        updated.status === "SUSPENDED"
          ? `${updated.name} has been suspended.`
          : `${updated.name} status restored to ${updated.status}.`,
      );
      await invalidatePlatformQueries(updated.id);
    },
    onError: (err) => {
      const actionable = formatActionableError(
        err,
        "Unable to change organization status.",
      );
      setDetailError({ title: actionable.title, message: actionable.message });
    },
  });

  const updateUserStatusMutation = useMutation({
    mutationFn: (payload: { orgId: number; userId: number; status: UserStatus }) =>
      service.updatePlatformOrganizationUserStatus(
        payload.orgId,
        payload.userId,
        payload.status,
      ),
    onSuccess: async (updatedUser) => {
      setDetailError(null);
      showSuccess(
        `${updatedUser.email} is now ${updatedUser.status.toLowerCase()}.`,
      );
      if (selectedOrgId !== null) {
        await invalidatePlatformQueries(selectedOrgId);
      }
    },
    onError: (err) => {
      const actionable = formatActionableError(
        err,
        "Unable to update user status.",
      );
      setDetailError({ title: actionable.title, message: actionable.message });
    },
  });

  const recoveryMutation = useMutation({
    mutationFn: (payload: { orgId: number; adminEmail: string }) =>
      service.createAdminRecoveryInvitation(payload.orgId, {
        adminEmail: payload.adminEmail,
      }),
    onSuccess: async (result) => {
      setDetailError(null);
      setRecoveryResult(result);
      setRecoveryEmail("");
      showSuccess(
        `Recovery invitation issued for ${result.invitedEmail}.`,
      );
      await invalidatePlatformQueries(result.organizationId);
    },
    onError: (err) => {
      const actionable = formatActionableError(
        err,
        "Unable to create recovery invitation.",
      );
      setDetailError({ title: actionable.title, message: actionable.message });
    },
  });

  const openProvisionModal = () => {
    setProvisionOpen(true);
    setOrgNameInput("");
    setBaseCurrencyInput("USD");
    setAdminEmailInput("");
    setProvisionError(null);
    setProvisionResult(null);
  };

  const openOrganizationDetail = (orgId: number) => {
    setSelectedOrgId(orgId);
    setEditOverrides({});
    setSuspendReason("");
    setReactivateNote("");
    setRecoveryEmail("");
    setRecoveryResult(null);
    setDetailError(null);
  };

  const handleProvisionSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setProvisionError(null);
    const trimmedName = orgNameInput.trim();
    const trimmedCurrency = baseCurrencyInput.trim().toUpperCase();
    const trimmedEmail = adminEmailInput.trim();

    if (!trimmedName) {
      setProvisionError("Organization name is required.");
      return;
    }
    if (!/^[A-Z]{3}$/.test(trimmedCurrency)) {
      setProvisionError("Base currency must be a 3-letter ISO code.");
      return;
    }
    if (!trimmedEmail || !trimmedEmail.includes("@")) {
      setProvisionError("Valid initial administrator email is required.");
      return;
    }

    provisionMutation.mutate({
      organizationName: trimmedName,
      baseCurrency: trimmedCurrency,
      adminEmail: trimmedEmail,
    });
  };

  const detail = detailQuery.data;
  const draftName = editOverrides.name ?? detail?.name ?? "";
  const draftCurrency =
    editOverrides.baseCurrency ?? detail?.baseCurrency ?? "";

  const handleSaveOrgProfile = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    setDetailError(null);

    const trimmedName = draftName.trim();
    const trimmedCurrency = draftCurrency.trim().toUpperCase();

    if (!trimmedName) {
      setDetailError({
        title: "Validation Failed",
        message: "Organization name is required.",
      });
      return;
    }
    if (!/^[A-Z]{3}$/.test(trimmedCurrency)) {
      setDetailError({
        title: "Invalid Currency Code",
        message: "Currency must be a 3-letter ISO code.",
      });
      return;
    }

    updateOrgMutation.mutate({
      orgId: detail.id,
      name: trimmedName,
      baseCurrency: trimmedCurrency,
    });
  };

  const handleSuspendOrg = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    const reason = suspendReason.trim();
    if (!reason) {
      setDetailError({
        title: "Suspension Reason Required",
        message: "Provide a documented reason before suspending a tenant.",
      });
      return;
    }
    updateOrgStatusMutation.mutate({
      orgId: detail.id,
      status: "SUSPENDED",
      reason,
    });
  };

  const handleRecoverySubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail) return;
    const email = recoveryEmail.trim();
    if (!email || !email.includes("@")) {
      setDetailError({
        title: "Validation Failed",
        message: "Enter a valid administrator email for recovery.",
      });
      return;
    }
    recoveryMutation.mutate({ orgId: detail.id, adminEmail: email });
  };

  return (
    <div className="space-y-6">
      <div className="page-card flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-slate-900 text-white">
            <Building2 className="h-5 w-5" />
          </div>
          <div>
            <p className="page-header-kicker">Tenant Lifecycle Management</p>
            <h2 className="page-title">Customer Organizations</h2>
          </div>
        </div>
        <button
          type="button"
          onClick={openProvisionModal}
          className="btn-primary"
        >
          <Plus className="h-4 w-4" />
          Provision organization
        </button>
      </div>

      <div className="page-card p-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative min-w-[220px] flex-1">
            <Search className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-slate-400" />
            <input
              type="text"
              aria-label="Search organizations"
              placeholder="Search organization name…"
              value={searchInput}
              onChange={(e) => {
                setSearchInput(e.target.value);
                setPage(1);
              }}
              className="input-control pl-9"
            />
          </div>

          <select
            aria-label="Filter by organization status"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value as OrganizationStatus | "");
              setPage(1);
            }}
            className="h-10 rounded-lg border border-slate-300 bg-white px-3 text-sm text-slate-700 outline-none focus:border-slate-500"
          >
            <option value="">All Statuses</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="PENDING">PENDING</option>
            <option value="SUSPENDED">SUSPENDED</option>
          </select>

          {(searchInput || statusFilter) && (
            <button
              type="button"
              onClick={() => {
                setSearchInput("");
                setStatusFilter("");
                setPage(1);
              }}
              className="btn-secondary text-xs"
            >
              Clear
            </button>
          )}
        </div>
      </div>

      {organizationsQuery.isLoading ? (
        <div className="page-card p-8 text-center text-sm text-slate-500">
          Loading customer organizations…
        </div>
      ) : organizationsQuery.isError ? (
        <div className="feedback-error" role="alert">
          {
            formatActionableError(
              organizationsQuery.error,
              "Failed to load customer organizations.",
            ).message
          }
        </div>
      ) : (
        <div className="space-y-4">
          <div className="table-wrap">
            <table className="min-w-full text-left text-sm">
              <thead className="table-head">
                <tr>
                  <th className="px-4 py-3 font-medium">Organization</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Users & Admins</th>
                  <th className="px-4 py-3 font-medium">Accounting Metrics</th>
                  <th className="px-4 py-3 font-medium">Open Invites</th>
                  <th className="px-4 py-3 font-medium">Last Activity</th>
                  <th className="px-4 py-3 text-right font-medium">Manage</th>
                </tr>
              </thead>
              <tbody>
                {(organizationsQuery.data?.items ?? []).length === 0 ? (
                  <tr>
                    <td
                      colSpan={7}
                      className="px-4 py-8 text-center text-slate-500"
                    >
                      No customer organizations found.
                    </td>
                  </tr>
                ) : (
                  (organizationsQuery.data?.items ?? []).map((org) => (
                    <tr key={org.id} className="border-t border-slate-200">
                      <td className="px-4 py-3">
                        <p className="font-semibold text-slate-900">
                          {org.name}
                        </p>
                        <p className="text-xs text-slate-500">
                          ID #{org.id} • {org.baseCurrency}
                        </p>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`status-badge ${ORG_STATUS_BADGE[org.status]}`}
                        >
                          {org.status}
                        </span>
                        {org.suspendedReason ? (
                          <p className="mt-1 max-w-[220px] truncate text-xs text-red-700">
                            {org.suspendedReason}
                          </p>
                        ) : null}
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-700">
                        <div>
                          {org.userCount} total • {org.activeAdminCount} active
                          admin
                        </div>
                        {org.disabledUserCount > 0 ? (
                          <div className="text-amber-700">
                            {org.disabledUserCount} disabled
                          </div>
                        ) : null}
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-700">
                        {org.accountCount} accts • {org.postedEntryCount}{" "}
                        posted • {org.draftEntryCount} draft
                      </td>
                      <td className="px-4 py-3 text-xs font-medium text-slate-700">
                        {org.pendingInvitationCount}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-xs text-slate-600">
                        {formatTimestamp(org.lastActivityAt)}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button
                          type="button"
                          onClick={() => openOrganizationDetail(org.id)}
                          className="btn-secondary px-3 py-1.5 text-xs"
                        >
                          Manage
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between px-2 text-xs text-slate-500">
            <div>
              Total: {organizationsQuery.data?.totalElements ?? 0} organizations
              | Page {organizationsQuery.data?.page ?? 1} of{" "}
              {organizationsQuery.data?.totalPages ?? 1}
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={(organizationsQuery.data?.page ?? 1) <= 1}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-slate-700 disabled:opacity-40"
              >
                <ChevronLeft className="h-3.5 w-3.5" />
                Previous
              </button>
              <button
                type="button"
                disabled={
                  (organizationsQuery.data?.page ?? 1) >=
                  (organizationsQuery.data?.totalPages ?? 1)
                }
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

      {provisionOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm">
          <div className="w-full max-w-lg rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
              <div>
                <h3 className="text-lg font-semibold text-slate-900">
                  Provision Customer Organization
                </h3>
                <p className="text-xs text-slate-500">
                  Invite-first onboarding: organization starts in PENDING status
                  until the invited administrator completes account setup.
                </p>
              </div>
              <button
                type="button"
                aria-label="Close provisioning modal"
                onClick={() => setProvisionOpen(false)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {provisionError ? (
              <div className="feedback-error mt-4" role="alert">
                {provisionError}
              </div>
            ) : null}

            {provisionResult ? (
              <div className="mt-4 space-y-4">
                <div className="feedback-success">
                  <p className="font-semibold">
                    {provisionResult.organizationName} created (
                    {provisionResult.status})
                  </p>
                  <p className="mt-1 text-xs">
                    Onboarding invitation issued for{" "}
                    <span className="font-semibold">
                      {provisionResult.invitedAdminEmail}
                    </span>
                    . Share the one-time token below securely—it is never shown
                    again.
                  </p>
                </div>

                <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 text-xs space-y-2">
                  <div>
                    <span className="font-semibold text-slate-600">
                      One-time Invitation Token:
                    </span>
                    <div
                      data-testid="onboarding-invitation-token"
                      className="mt-1 select-all break-all rounded border border-slate-200 bg-white p-2 font-mono text-slate-900"
                    >
                      {provisionResult.invitationToken}
                    </div>
                  </div>
                  <div>
                    <span className="font-semibold text-slate-600">
                      Onboarding URL:
                    </span>
                    <div className="mt-1 select-all break-all rounded border border-slate-200 bg-white p-2 font-mono text-slate-700">
                      {`/accept-invitation?token=${encodeURIComponent(
                        provisionResult.invitationToken,
                      )}&email=${encodeURIComponent(
                        provisionResult.invitedAdminEmail,
                      )}`}
                    </div>
                  </div>
                  <p className="text-slate-500">
                    Expires: {formatTimestamp(provisionResult.invitationExpiresAt)}
                  </p>
                </div>

                <div className="flex justify-end">
                  <button
                    type="button"
                    onClick={() => setProvisionOpen(false)}
                    className="btn-primary"
                  >
                    Done
                  </button>
                </div>
              </div>
            ) : (
              <form
                onSubmit={handleProvisionSubmit}
                className="mt-4 space-y-4"
                noValidate
              >
                <div>
                  <label
                    htmlFor="provision-org-name"
                    className="block text-sm font-medium text-slate-700"
                  >
                    Organization name
                  </label>
                  <input
                    id="provision-org-name"
                    type="text"
                    required
                    value={orgNameInput}
                    onChange={(e) => setOrgNameInput(e.target.value)}
                    placeholder="Acme Corp Ltd."
                    className="input-control mt-1"
                  />
                </div>

                <div>
                  <label
                    htmlFor="provision-base-currency"
                    className="block text-sm font-medium text-slate-700"
                  >
                    Base currency (3-letter ISO)
                  </label>
                  <input
                    id="provision-base-currency"
                    type="text"
                    required
                    maxLength={3}
                    value={baseCurrencyInput}
                    onChange={(e) =>
                      setBaseCurrencyInput(e.target.value.toUpperCase())
                    }
                    placeholder="USD"
                    className="input-control mt-1 uppercase"
                  />
                </div>

                <div>
                  <label
                    htmlFor="provision-admin-email"
                    className="block text-sm font-medium text-slate-700"
                  >
                    Initial administrator email
                  </label>
                  <input
                    id="provision-admin-email"
                    type="email"
                    required
                    value={adminEmailInput}
                    onChange={(e) => setAdminEmailInput(e.target.value)}
                    placeholder="founder@acmecorp.com"
                    className="input-control mt-1"
                  />
                  <p className="mt-1 text-xs text-slate-500">
                    The invited customer administrator sets their own name and
                    password when accepting the invitation.
                  </p>
                </div>

                <div className="flex justify-end gap-3 pt-2">
                  <button
                    type="button"
                    onClick={() => setProvisionOpen(false)}
                    className="btn-secondary"
                    disabled={provisionMutation.isPending}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={provisionMutation.isPending}
                  >
                    {provisionMutation.isPending
                      ? "Provisioning…"
                      : "Create & Issue Onboarding Invite"}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      ) : null}

      {selectedOrgId !== null ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm">
          <div className="max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-2xl border border-slate-200 bg-white p-6 shadow-xl space-y-6">
            <div className="flex items-start justify-between border-b border-slate-200 pb-4">
              <div>
                <p className="page-header-kicker">
                  Organization Governance #{selectedOrgId}
                </p>
                <h3 className="mt-1 text-xl font-semibold text-slate-900">
                  {detail?.name ?? "Loading organization…"}
                </h3>
                {detail ? (
                  <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                    <span
                      className={`status-badge ${ORG_STATUS_BADGE[detail.status]}`}
                    >
                      {detail.status}
                    </span>
                    <span
                      className={`status-badge ${
                        detail.trialBalanceBalanced
                          ? "status-active"
                          : "bg-amber-100 text-amber-800"
                      }`}
                    >
                      {detail.trialBalanceBalanced ? (
                        <span className="inline-flex items-center gap-1">
                          <CheckCircle2 className="h-3.5 w-3.5" />
                          Trial balance balanced
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1">
                          <AlertTriangle className="h-3.5 w-3.5" />
                          Trial balance unbalanced
                        </span>
                      )}
                    </span>
                    <span className="text-slate-500">
                      {detail.accountCount} accounts • {detail.postedEntryCount}{" "}
                      posted • {detail.draftEntryCount} draft
                    </span>
                  </div>
                ) : null}
              </div>

              <button
                type="button"
                aria-label="Close organization details"
                onClick={() => setSelectedOrgId(null)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {detailError ? (
              <div className="feedback-error" role="alert">
                <p className="font-semibold">{detailError.title}</p>
                <p className="mt-1">{detailError.message}</p>
              </div>
            ) : null}

            {detailQuery.isLoading ? (
              <p className="text-sm text-slate-500">
                Loading organization details…
              </p>
            ) : detail ? (
              <>
                {detail.status === "SUSPENDED" ? (
                  <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
                    <div className="flex items-start gap-2">
                      <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0" />
                      <div>
                        <p className="font-semibold">
                          Organization suspended{" "}
                          {detail.suspendedAt
                            ? `on ${formatTimestamp(detail.suspendedAt)}`
                            : ""}
                        </p>
                        {detail.suspendedReason ? (
                          <p className="mt-1 text-xs">
                            Reason: {detail.suspendedReason}
                          </p>
                        ) : null}
                      </div>
                    </div>
                  </div>
                ) : null}

                <div className="grid gap-6 lg:grid-cols-2">
                  <form
                    onSubmit={handleSaveOrgProfile}
                    className="rounded-xl border border-slate-200 p-4 space-y-3"
                  >
                    <h4 className="text-sm font-semibold text-slate-900">
                      Organization Profile
                    </h4>
                    <div>
                      <label
                        htmlFor="detail-org-name"
                        className="block text-xs font-medium text-slate-600"
                      >
                        Organization name
                      </label>
                      <input
                        id="detail-org-name"
                        type="text"
                        value={draftName}
                        onChange={(e) =>
                          setEditOverrides((prev) => ({
                            ...prev,
                            name: e.target.value,
                          }))
                        }
                        className="input-control mt-1"
                      />
                    </div>
                    <div>
                      <label
                        htmlFor="detail-org-currency"
                        className="block text-xs font-medium text-slate-600"
                      >
                        Base currency
                      </label>
                      <input
                        id="detail-org-currency"
                        type="text"
                        maxLength={3}
                        disabled={detail.hasTransactions}
                        value={draftCurrency}
                        onChange={(e) =>
                          setEditOverrides((prev) => ({
                            ...prev,
                            baseCurrency: e.target.value.toUpperCase(),
                          }))
                        }
                        className="input-control mt-1 uppercase disabled:bg-slate-100"
                      />
                      {detail.hasTransactions ? (
                        <p className="mt-1 text-xs text-slate-500">
                          Locked because journal transactions already exist.
                        </p>
                      ) : null}
                    </div>
                    <button
                      type="submit"
                      disabled={updateOrgMutation.isPending}
                      className="btn-secondary text-xs"
                    >
                      <ShieldCheck className="h-3.5 w-3.5" />
                      {updateOrgMutation.isPending
                        ? "Saving…"
                        : "Save profile"}
                    </button>
                  </form>

                  <div className="rounded-xl border border-slate-200 p-4 space-y-3">
                    <h4 className="text-sm font-semibold text-slate-900">
                      Lifecycle Status Control
                    </h4>
                    {detail.status === "SUSPENDED" ? (
                      <div className="space-y-3">
                        <p className="text-xs text-slate-600">
                          Reactivation restores ACTIVE status when at least one
                          ACTIVE ADMIN exists (or PENDING if onboarding never
                          completed).
                        </p>
                        <input
                          type="text"
                          aria-label="Reactivation note"
                          placeholder="Optional reactivation note"
                          value={reactivateNote}
                          onChange={(e) => setReactivateNote(e.target.value)}
                          className="input-control"
                        />
                        <button
                          type="button"
                          disabled={updateOrgStatusMutation.isPending}
                          onClick={() =>
                            updateOrgStatusMutation.mutate({
                              orgId: detail.id,
                              status: "ACTIVE",
                              reason: reactivateNote.trim() || undefined,
                            })
                          }
                          className="btn-primary text-xs"
                        >
                          {updateOrgStatusMutation.isPending
                            ? "Reactivating…"
                            : "Reactivate organization"}
                        </button>
                      </div>
                    ) : (
                      <form onSubmit={handleSuspendOrg} className="space-y-3">
                        <p className="text-xs text-slate-600">
                          Suspending immediately blocks all tenant users and
                          revokes refresh token families.
                        </p>
                        <input
                          type="text"
                          aria-label="Suspension reason"
                          required
                          placeholder="Required suspension reason (e.g. Billing hold)"
                          value={suspendReason}
                          onChange={(e) => setSuspendReason(e.target.value)}
                          className="input-control"
                        />
                        <button
                          type="submit"
                          disabled={updateOrgStatusMutation.isPending}
                          className="btn-danger text-xs"
                        >
                          {updateOrgStatusMutation.isPending
                            ? "Suspending…"
                            : "Suspend organization"}
                        </button>
                      </form>
                    )}
                  </div>
                </div>

                <div className="space-y-3">
                  <div className="flex items-center justify-between">
                    <h4 className="text-sm font-semibold text-slate-900">
                      Organization Users ({orgUsersQuery.data?.length ?? 0})
                    </h4>
                  </div>

                  {orgUsersQuery.isLoading ? (
                    <p className="text-xs text-slate-500">Loading users…</p>
                  ) : (orgUsersQuery.data ?? []).length === 0 ? (
                    <div className="rounded-xl border border-dashed border-slate-300 p-4 text-center text-xs text-slate-500">
                      No users have completed onboarding for this organization
                      yet.
                    </div>
                  ) : (
                    <div className="table-wrap">
                      <table className="min-w-full text-left text-xs">
                        <thead className="table-head">
                          <tr>
                            <th className="px-3 py-2 font-medium">Name</th>
                            <th className="px-3 py-2 font-medium">Email</th>
                            <th className="px-3 py-2 font-medium">Role</th>
                            <th className="px-3 py-2 font-medium">Status</th>
                            <th className="px-3 py-2 font-medium">Joined</th>
                            <th className="px-3 py-2 text-right font-medium">
                              Action
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {(orgUsersQuery.data ?? []).map((user) => (
                            <tr
                              key={user.id}
                              className="border-t border-slate-200"
                            >
                              <td className="px-3 py-2 font-medium text-slate-800">
                                {user.fullName}
                              </td>
                              <td className="px-3 py-2 text-slate-600">
                                {user.email}
                              </td>
                              <td className="px-3 py-2">
                                <span className="status-badge status-inactive">
                                  {user.role}
                                </span>
                              </td>
                              <td className="px-3 py-2">
                                <span
                                  className={`status-badge ${
                                    user.status === "ACTIVE"
                                      ? "status-active"
                                      : "bg-red-100 text-red-700"
                                  }`}
                                >
                                  {user.status}
                                </span>
                              </td>
                              <td className="px-3 py-2 text-slate-500">
                                {formatDate(user.createdAt)}
                              </td>
                              <td className="px-3 py-2 text-right">
                                <div className="inline-flex items-center gap-1.5">
                                  {user.status === "ACTIVE" ? (
                                    <button
                                      type="button"
                                      disabled={
                                        updateUserStatusMutation.isPending
                                      }
                                      onClick={() =>
                                        updateUserStatusMutation.mutate({
                                          orgId: detail.id,
                                          userId: user.id,
                                          status: "DISABLED",
                                        })
                                      }
                                      className="btn-danger px-2 py-1 text-xs"
                                    >
                                      <UserX className="h-3.5 w-3.5" />
                                      Disable
                                    </button>
                                  ) : (
                                    <>
                                      <button
                                        type="button"
                                        disabled={
                                          updateUserStatusMutation.isPending
                                        }
                                        onClick={() =>
                                          updateUserStatusMutation.mutate({
                                            orgId: detail.id,
                                            userId: user.id,
                                            status: "ACTIVE",
                                          })
                                        }
                                        className="btn-secondary px-2 py-1 text-xs"
                                      >
                                        <UserCheck className="h-3.5 w-3.5" />
                                        Enable
                                      </button>
                                      {user.role === "ADMIN" ? (
                                        <button
                                          type="button"
                                          onClick={() => {
                                            setRecoveryEmail(user.email);
                                            setRecoveryResult(null);
                                          }}
                                          className="btn-secondary px-2 py-1 text-xs"
                                        >
                                          <KeyRound className="h-3.5 w-3.5" />
                                          Recover
                                        </button>
                                      ) : null}
                                    </>
                                  )}
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>

                <div className="rounded-xl border border-slate-200 p-4 space-y-3">
                  <div className="flex items-center gap-2">
                    <KeyRound className="h-4 w-4 text-slate-600" />
                    <h4 className="text-sm font-semibold text-slate-900">
                      Administrator Recovery Invitation
                    </h4>
                  </div>
                  <p className="text-xs text-slate-600">
                    Issue a one-time ADMIN recovery invitation for a disabled
                    administrator&apos;s email (updates that exact user in
                    place) or for a replacement administrator email.
                  </p>
                  <form
                    onSubmit={handleRecoverySubmit}
                    className="flex flex-col gap-2 sm:flex-row"
                  >
                    <input
                      type="email"
                      aria-label="Recovery administrator email"
                      required
                      placeholder="recovery-admin@company.com"
                      value={recoveryEmail}
                      onChange={(e) => setRecoveryEmail(e.target.value)}
                      className="input-control flex-1"
                    />
                    <button
                      type="submit"
                      disabled={recoveryMutation.isPending}
                      className="btn-primary text-xs"
                    >
                      {recoveryMutation.isPending
                        ? "Issuing…"
                        : "Issue recovery invitation"}
                    </button>
                  </form>

                  {recoveryResult ? (
                    <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-xs space-y-2">
                      <p className="font-semibold text-emerald-800">
                        Recovery invitation created for{" "}
                        {recoveryResult.invitedEmail} (
                        {recoveryResult.targetUserId !== null
                          ? `in-place recovery for user #${recoveryResult.targetUserId}`
                          : "new administrator"}
                        )
                      </p>
                      <div
                        data-testid="recovery-invitation-token"
                        className="select-all break-all rounded border border-emerald-200 bg-white p-2 font-mono text-slate-900"
                      >
                        {recoveryResult.invitationToken}
                      </div>
                      <p className="text-emerald-800">
                        Expires: {formatTimestamp(recoveryResult.expiresAt)}
                      </p>
                    </div>
                  ) : null}
                </div>

                {detail.pendingInvitations.length > 0 ? (
                  <div className="space-y-2">
                    <h4 className="text-sm font-semibold text-slate-900">
                      Open Invitations ({detail.pendingInvitations.length})
                    </h4>
                    <div className="table-wrap">
                      <table className="min-w-full text-left text-xs">
                        <thead className="table-head">
                          <tr>
                            <th className="px-3 py-2 font-medium">Email</th>
                            <th className="px-3 py-2 font-medium">Purpose</th>
                            <th className="px-3 py-2 font-medium">Role</th>
                            <th className="px-3 py-2 font-medium">Expires</th>
                          </tr>
                        </thead>
                        <tbody>
                          {detail.pendingInvitations.map((inv) => (
                            <tr
                              key={inv.id}
                              className="border-t border-slate-200"
                            >
                              <td className="px-3 py-2 font-medium text-slate-800">
                                {inv.invitedEmail}
                              </td>
                              <td className="px-3 py-2 font-mono text-slate-600">
                                {inv.purpose}
                              </td>
                              <td className="px-3 py-2">{inv.role}</td>
                              <td className="px-3 py-2 text-slate-500">
                                {formatTimestamp(inv.expiresAt)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                ) : null}
              </>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
};
