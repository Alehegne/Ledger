import { type FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, ShieldCheck, Trash2 } from "lucide-react";
import { useLocation } from "react-router-dom";
import { useAuth } from "../../app/auth-context";
import { useToast } from "../../app/toast-store";
import { formatDate } from "../../lib/date";
import { env } from "../../lib/env";
import { getErrorMessage, UnsupportedInApiModeError } from "../../lib/errors";
import { useLedgerService } from "../../services/interfaces/service-context";
import type { Role } from "../../types/domain";

const UnsupportedAccessState = ({
  title,
  message,
}: {
  title: string;
  message: string;
}) => (
  <div className="rounded-2xl border border-amber-200 bg-amber-50 p-6 text-sm text-amber-900">
    <p className="font-medium">{title}</p>
    <p className="mt-2">{message}</p>
  </div>
);

const toUnavailableMessage = (error: unknown, fallback: string) => {
  if (error instanceof UnsupportedInApiModeError) {
    return fallback;
  }

  const message = error instanceof Error ? error.message : "";
  if (/not available|not supported|not enabled/i.test(message)) {
    return fallback;
  }

  return message || "This capability is unavailable.";
};

export const OrganizationPage = () => {
  const service = useLedgerService();
  const queryClient = useQueryClient();
  const { session, setSession } = useAuth();
  const { showSuccess } = useToast();
  const location = useLocation();
  const isSettingsView =
    location.pathname === "/settings" ||
    location.pathname === "/organization/settings";
  const isApiMode = env.mode === "api";
  const canManageSettings = session?.user.role === "ADMIN";
  const [formError, setFormError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [draftOverride, setDraftOverride] = useState<
    Partial<{ name: string; baseCurrency: string }>
  >({});
  const [inviteModalOpen, setInviteModalOpen] = useState(false);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<Role>("ACCOUNTANT");
  const [inviteError, setInviteError] = useState<string | null>(null);
  const [inviteSuccess, setInviteSuccess] = useState<string | null>(null);
  const [lastCreatedToken, setLastCreatedToken] = useState<string | null>(null);
  const [roleUpdateError, setRoleUpdateError] = useState<string | null>(null);

  const {
    data: settings,
    isLoading: settingsLoading,
    isError: settingsError,
    error: settingsErrorData,
  } = useQuery({
    queryKey: ["organizationSettings"],
    queryFn: () => service.getOrganizationSettings(),
    retry: false,
  });

  const usersQuery = useQuery({
    queryKey: ["organizationUsers"],
    queryFn: () => service.listOrganizationUsers(),
    retry: false,
    enabled: !isSettingsView,
  });

  const invitationsQuery = useQuery({
    queryKey: ["pendingInvitations"],
    queryFn: () => service.listPendingInvitations(),
    retry: false,
    enabled: !isSettingsView && canManageSettings,
  });

  const createInvitationMutation = useMutation({
    mutationFn: (payload: { email: string; role: Role }) =>
      service.createInvitation(payload),
    onSuccess: (invitation) => {
      queryClient.invalidateQueries({ queryKey: ["pendingInvitations"] });
      setLastCreatedToken(invitation.invitationToken);
      const message = `Invitation created for ${invitation.invitedEmail}.`;
      setInviteSuccess(message);
      setInviteError(null);
      showSuccess(message);
    },
    onError: (err) => {
      setInviteError(getErrorMessage(err, "Failed to create invitation."));
    },
  });

  const revokeInvitationMutation = useMutation({
    mutationFn: (invitationId: number) =>
      service.revokeInvitation(invitationId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["pendingInvitations"] });
      showSuccess("Invitation revoked.");
    },
  });

  const roleUpdateMutation = useMutation({
    mutationFn: ({ userId, role }: { userId: number; role: Role }) =>
      service.updateUserRole(userId, role),
    onSuccess: (updatedUser) => {
      queryClient.invalidateQueries({ queryKey: ["organizationUsers"] });
      setRoleUpdateError(null);
      showSuccess(`Updated role for ${updatedUser.fullName} to ${updatedUser.role}.`);
    },
    onError: (err) => {
      setRoleUpdateError(getErrorMessage(err, "Failed to update user role."));
    },
  });

  const draft = {
    name: draftOverride.name ?? settings?.name ?? "",
    baseCurrency: draftOverride.baseCurrency ?? settings?.baseCurrency ?? "",
  };

  useEffect(() => {
    if (
      settings &&
      session &&
      (session.user.organizationName !== settings.name ||
        session.user.baseCurrency !== settings.baseCurrency)
    ) {
      setSession({
        ...session,
        user: {
          ...session.user,
          organizationName: settings.name,
          baseCurrency: settings.baseCurrency,
        },
      });
    }
  }, [settings, session, setSession]);

  const saveMutation = useMutation({
    mutationFn: (payload: Partial<{ name: string; baseCurrency: string }>) =>
      service.updateOrganizationSettings(payload),
    onSuccess: async (updated) => {
      setSuccessMessage("Organization settings saved.");
      showSuccess("Organization settings saved.");
      setFormError(null);
      if (session) {
        setSession({
          ...session,
          user: {
            ...session.user,
            organizationName: updated.name,
            baseCurrency: updated.baseCurrency,
          },
        });
      }
      await queryClient.setQueryData(["organizationSettings"], updated);
      setDraftOverride({});
    },
    onError: (error) => {
      setSuccessMessage(null);
      setFormError(
        getErrorMessage(error, "Unable to save organization settings."),
      );
    },
  });

  if (settingsLoading) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-500">
        Loading organization settings…
      </div>
    );
  }

  if (isSettingsView) {
    if (settingsError) {
      const message = toUnavailableMessage(
        settingsErrorData,
        "Organization settings are not available in the current API contract.",
      );

      return (
        <UnsupportedAccessState
          title="Settings unavailable"
          message={message}
        />
      );
    }

    const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      setFormError(null);
      setSuccessMessage(null);

      const name = draft.name.trim();
      const baseCurrency = draft.baseCurrency.trim().toUpperCase();

      if (!name) {
        setFormError("Organization name is required.");
        return;
      }

      if (!/^[A-Z]{3}$/.test(baseCurrency)) {
        setFormError("Currency must be a 3-letter ISO code.");
        return;
      }

      saveMutation.mutate({
        name,
        baseCurrency,
      });
    };

    return (
      <div className="space-y-6">
        <div className="page-card p-5">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
                Organization
              </p>
              <h2 className="mt-1 text-2xl font-semibold text-slate-900">
                Settings
              </h2>
            </div>
            {canManageSettings ? (
              <button
                type="submit"
                form="organization-settings-form"
                className="btn-primary disabled:cursor-not-allowed disabled:opacity-60"
                disabled={saveMutation.isPending}
              >
                <ShieldCheck className="h-4 w-4" />
                {saveMutation.isPending ? "Saving…" : "Save changes"}
              </button>
            ) : null}
          </div>

          <form
            id="organization-settings-form"
            onSubmit={handleSubmit}
            className="space-y-5"
          >
            {formError ? (
              <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
                {formError}
              </div>
            ) : null}

            {successMessage ? (
              <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-700">
                {successMessage}
              </div>
            ) : null}

            <div className="grid gap-4 md:grid-cols-2">
              <label className="block text-sm font-medium text-slate-700">
                Organization name
                <input
                  aria-label="Organization name"
                  className="mt-1 w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-slate-900 outline-none ring-0 transition focus:border-slate-500"
                  value={draft.name}
                  onChange={(event) =>
                    setDraftOverride((current) => ({
                      ...current,
                      name: event.target.value,
                    }))
                  }
                  disabled={!canManageSettings || saveMutation.isPending}
                />
              </label>

              <label className="block text-sm font-medium text-slate-700">
                Base currency
                <input
                  aria-label="Base currency"
                  className="mt-1 w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-slate-900 uppercase outline-none ring-0 transition focus:border-slate-500"
                  value={draft.baseCurrency}
                  onChange={(event) =>
                    setDraftOverride((current) => ({
                      ...current,
                      baseCurrency: event.target.value,
                    }))
                  }
                  disabled={!canManageSettings || saveMutation.isPending}
                  maxLength={3}
                />
                <span className="mt-1 block text-xs text-slate-500">
                  Base currency is locked once journal transactions exist.
                </span>
              </label>
            </div>

            {isApiMode ? (
              <p className="text-xs text-slate-500">
                Organization settings are saved through the verified admin-only
                API contract.
              </p>
            ) : null}
          </form>
        </div>
      </div>
    );
  }

  if (usersQuery.isError) {
    const message = toUnavailableMessage(
      usersQuery.error,
      "User management is not available in the current API contract.",
    );

    return (
      <UnsupportedAccessState title="Users unavailable" message={message} />
    );
  }

  return (
    <div className="space-y-6">
      <div className="page-card flex items-center justify-between p-5">
        <div>
          <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
            Access
          </p>
          <h2 className="mt-1 text-2xl font-semibold text-slate-900">
            Users & invitations
          </h2>
        </div>
        {canManageSettings ? (
          <button
            type="button"
            onClick={() => {
              setInviteModalOpen(true);
              setInviteEmail("");
              setInviteRole("ACCOUNTANT");
              setInviteError(null);
              setInviteSuccess(null);
              setLastCreatedToken(null);
            }}
            className="btn-primary"
          >
            <Plus className="h-4 w-4" />
            Invite member
          </button>
        ) : null}
      </div>

      <div className="space-y-3">
        <h3 className="text-lg font-semibold text-slate-900">Active users</h3>
        {roleUpdateError ? (
          <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {roleUpdateError}
          </div>
        ) : null}
        {usersQuery.isLoading ? (
          <div className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-500">
            Loading organization users…
          </div>
        ) : (
          <div className="table-wrap">
            <table className="min-w-full text-left text-sm">
              <thead className="table-head">
                <tr>
                  <th className="px-4 py-3 font-medium">Name</th>
                  <th className="px-4 py-3 font-medium">Email</th>
                  <th className="px-4 py-3 font-medium">Role</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {(usersQuery.data ?? []).map((user) => (
                  <tr key={user.id} className="border-t border-slate-200">
                    <td className="px-4 py-3 font-medium text-slate-800">
                      {user.fullName}
                    </td>
                    <td className="px-4 py-3 text-slate-600">{user.email}</td>
                    <td className="px-4 py-3">
                      {canManageSettings ? (
                        <select
                          aria-label={`Role for ${user.fullName}`}
                          className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs font-semibold text-slate-700 outline-none transition focus:border-slate-500"
                          value={user.role}
                          disabled={roleUpdateMutation.isPending}
                          onChange={(e) => {
                            const newRole = e.target.value as Role;
                            if (newRole !== user.role) {
                              roleUpdateMutation.mutate({
                                userId: user.id,
                                role: newRole,
                              });
                            }
                          }}
                        >
                          <option value="ADMIN">ADMIN</option>
                          <option value="ACCOUNTANT">ACCOUNTANT</option>
                          <option value="VIEWER">VIEWER</option>
                        </select>
                      ) : (
                        <span className="status-badge status-inactive">
                          {user.role}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`status-badge ${
                          user.invitationStatus === "ACTIVE"
                            ? "status-active"
                            : "status-draft"
                        }`}
                      >
                        {user.invitationStatus}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {canManageSettings ? (
        <div className="space-y-3">
          <h3 className="text-lg font-semibold text-slate-900">
            Pending invitations
          </h3>
          {invitationsQuery.isLoading ? (
            <p className="text-sm text-slate-500">Loading invitations…</p>
          ) : (invitationsQuery.data ?? []).length === 0 ? (
            <div className="rounded-xl border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
              No pending invitations.
            </div>
          ) : (
            <div className="table-wrap">
              <table className="min-w-full text-left text-sm">
                <thead className="table-head">
                  <tr>
                    <th className="px-4 py-3 font-medium">Invited Email</th>
                    <th className="px-4 py-3 font-medium">Role</th>
                    <th className="px-4 py-3 font-medium">Expires At</th>
                    <th className="px-4 py-3 text-right font-medium">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {(invitationsQuery.data ?? []).map((inv) => (
                    <tr key={inv.id} className="border-t border-slate-200">
                      <td className="px-4 py-3 font-medium text-slate-800">
                        {inv.invitedEmail}
                      </td>
                      <td className="px-4 py-3">
                        <span className="status-badge status-inactive">
                          {inv.role}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-slate-600">
                        {formatDate(inv.expiresAt)}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button
                          type="button"
                          onClick={() => revokeInvitationMutation.mutate(inv.id)}
                          disabled={revokeInvitationMutation.isPending}
                          className="btn-danger p-1.5 text-xs"
                          aria-label={`Revoke invitation for ${inv.invitedEmail}`}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}

      {inviteModalOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div className="w-full max-w-md space-y-4 rounded-2xl bg-white p-6 shadow-xl">
            <h3 className="text-lg font-semibold text-slate-900">
              Invite Team Member
            </h3>
            <p className="text-sm text-slate-600">
              An invitation will be created allowing the invited user to set up
              their account.
            </p>

            {inviteError ? (
              <div className="rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-700">
                {inviteError}
              </div>
            ) : null}

            {inviteSuccess ? (
              <div className="space-y-2 rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-700">
                <p>{inviteSuccess}</p>
                {lastCreatedToken ? (
                  <div className="mt-2 text-xs">
                    <p className="font-semibold text-slate-700">
                      Invitation Token:
                    </p>
                    <div className="mt-1 select-all break-all rounded border border-slate-200 bg-slate-50 p-2 font-mono">
                      {lastCreatedToken}
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}

            {!lastCreatedToken ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  createInvitationMutation.mutate({
                    email: inviteEmail.trim(),
                    role: inviteRole,
                  });
                }}
                className="space-y-4"
              >
                <div>
                  <label className="block text-sm font-medium text-slate-700">
                    Email address
                  </label>
                  <input
                    type="email"
                    required
                    value={inviteEmail}
                    onChange={(e) => setInviteEmail(e.target.value)}
                    placeholder="colleague@example.com"
                    className="input-control mt-1 w-full"
                    autoFocus
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700">
                    Role
                  </label>
                  <select
                    value={inviteRole}
                    onChange={(e) => setInviteRole(e.target.value as Role)}
                    className="input-control mt-1 w-full"
                  >
                    <option value="ACCOUNTANT">Accountant</option>
                    <option value="VIEWER">Viewer</option>
                    <option value="ADMIN">Admin</option>
                  </select>
                </div>
                <div className="flex justify-end gap-3 pt-2">
                  <button
                    type="button"
                    onClick={() => setInviteModalOpen(false)}
                    className="btn-secondary"
                    disabled={createInvitationMutation.isPending}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={
                      createInvitationMutation.isPending || !inviteEmail.trim()
                    }
                  >
                    {createInvitationMutation.isPending
                      ? "Inviting..."
                      : "Send Invitation"}
                  </button>
                </div>
              </form>
            ) : (
              <div className="flex justify-end pt-2">
                <button
                  type="button"
                  onClick={() => setInviteModalOpen(false)}
                  className="btn-primary"
                >
                  Done
                </button>
              </div>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
};
