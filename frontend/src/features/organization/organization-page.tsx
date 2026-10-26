import { type FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ShieldCheck } from "lucide-react";
import { useLocation } from "react-router-dom";
import { useAuth } from "../../app/auth-context";
import { env } from "../../lib/env";
import { UnsupportedInApiModeError } from "../../lib/errors";
import { useLedgerService } from "../../services/interfaces/service-context";

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
  const location = useLocation();
  const isSettingsView =
    location.pathname === "/settings" ||
    location.pathname === "/organization/settings";
  const isApiMode = env.mode === "api";
  const canManageSettings = session?.user.role === "ADMIN";
  const [formError, setFormError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [draft, setDraft] = useState({ name: "", baseCurrency: "" });

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

  useEffect(() => {
    if (settings) {
      const organizationName = settings.name;
      setDraft({
        name: organizationName,
        baseCurrency: settings.baseCurrency,
      });

      if (
        session &&
        session.user.organizationName !== undefined &&
        session.user.organizationName !== organizationName
      ) {
        setSession({
          ...session,
          user: {
            ...session.user,
            organizationName,
          },
        });
      }

      if (session && session.user.organizationName === undefined) {
        setSession({
          ...session,
          user: {
            ...session.user,
            organizationName,
          },
        });
      }
    }
  }, [settings, session, setSession]);

  const saveMutation = useMutation({
    mutationFn: (payload: Partial<{ name: string; baseCurrency: string }>) =>
      service.updateOrganizationSettings(payload),
    onSuccess: async (updated) => {
      setSuccessMessage("Organization settings saved.");
      setFormError(null);
      if (session) {
        setSession({
          ...session,
          user: {
            ...session.user,
            organizationName: updated.name,
          },
        });
      }
      await queryClient.setQueryData(["organizationSettings"], updated);
      setDraft({ name: updated.name, baseCurrency: updated.baseCurrency });
    },
    onError: (error) => {
      setSuccessMessage(null);
      setFormError(
        error instanceof Error
          ? error.message
          : "Unable to save organization settings.",
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
                    setDraft((current) => ({
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
                    setDraft((current) => ({
                      ...current,
                      baseCurrency: event.target.value,
                    }))
                  }
                  disabled={!canManageSettings || saveMutation.isPending}
                  maxLength={3}
                />
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
      </div>

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
                    <span className="status-badge status-inactive">
                      {user.role}
                    </span>
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
  );
};
