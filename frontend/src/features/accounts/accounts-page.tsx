import { type FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Power, X } from "lucide-react";
import { useAuth } from "../../app/auth-context";
import { useToast } from "../../app/toast-store";
import { getErrorMessage } from "../../lib/errors";
import { decimal, formatMoney } from "../../lib/money";
import { useLedgerService } from "../../services/interfaces/service-context";
import type { Account } from "../../types/domain";

type AccountFormState = {
  accountCode: string;
  name: string;
  accountType: Account["accountType"];
  status: Account["status"];
  parentAccountId: string;
};

const accountTypeOptions: Account["accountType"][] = [
  "ASSET",
  "LIABILITY",
  "EQUITY",
  "REVENUE",
  "EXPENSE",
];

const defaultFormState: AccountFormState = {
  accountCode: "",
  name: "",
  accountType: "ASSET",
  status: "ACTIVE",
  parentAccountId: "",
};

export const AccountsPage = () => {
  const service = useLedgerService();
  const queryClient = useQueryClient();
  const { session } = useAuth();
  const { showSuccess } = useToast();
  const canManageAccounts = session?.user.role === "ADMIN";
  const [formState, setFormState] =
    useState<AccountFormState>(defaultFormState);
  const [editingAccountId, setEditingAccountId] = useState<number | null>(null);
  const [formVisible, setFormVisible] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const {
    data: accounts = [],
    isLoading,
    isError,
    error,
  } = useQuery({
    queryKey: ["accounts"],
    queryFn: () => service.listAccounts(),
  });

  const { data: trialBalance } = useQuery({
    queryKey: ["trialBalance", "accounts-page"],
    queryFn: () => service.getTrialBalance(),
  });

  const invalidateAccountingViews = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["accounts"] }),
      queryClient.invalidateQueries({ queryKey: ["overview"] }),
      queryClient.invalidateQueries({ queryKey: ["trialBalance"] }),
      queryClient.invalidateQueries({ queryKey: ["journalEntries"] }),
      queryClient.invalidateQueries({ queryKey: ["generalLedger"] }),
    ]);
  };

  const createMutation = useMutation({
    mutationFn: (payload: {
      accountCode: string;
      name: string;
      accountType: Account["accountType"];
      status: Account["status"];
      parentAccountId?: number | null;
    }) => service.createAccount(payload),
    onSuccess: async () => {
      await invalidateAccountingViews();
      setSuccessMessage("Account created successfully.");
      showSuccess("Account created successfully.");
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({
      accountId,
      payload,
    }: {
      accountId: number;
      payload: Partial<{
        accountCode: string;
        name: string;
        accountType: Account["accountType"];
        status: Account["status"];
        parentAccountId: number | null;
      }>;
    }) => service.updateAccount(accountId, payload),
    onSuccess: async () => {
      await invalidateAccountingViews();
      setSuccessMessage("Account updated successfully.");
      showSuccess("Account updated successfully.");
    },
  });

  const deactivateMutation = useMutation({
    mutationFn: (accountId: number) => service.deactivateAccount(accountId),
    onSuccess: async () => {
      await invalidateAccountingViews();
      setSuccessMessage("Account deactivated successfully.");
      showSuccess("Account deactivated successfully.");
    },
  });

  const accountById = useMemo(
    () => new Map(accounts.map((account) => [account.id, account])),
    [accounts],
  );

  const balanceByAccountId = useMemo(() => {
    const rows = trialBalance?.accounts ?? [];
    const map = new Map<number, string>();
    for (const row of rows) {
      const net = decimal(row.debitBalance).minus(decimal(row.creditBalance));
      map.set(row.accountId, net.toFixed(2));
    }
    return map;
  }, [trialBalance]);

  const resetForm = () => {
    setFormState(defaultFormState);
    setEditingAccountId(null);
    setFormError(null);
    setFormVisible(false);
  };

  const openCreateForm = () => {
    setSuccessMessage(null);
    setFormError(null);
    setEditingAccountId(null);
    setFormState(defaultFormState);
    setFormVisible(true);
  };

  const openEditForm = (account: Account) => {
    setSuccessMessage(null);
    setFormError(null);
    setEditingAccountId(account.id);
    setFormState({
      accountCode: account.accountCode,
      name: account.name,
      accountType: account.accountType,
      status: account.status,
      parentAccountId: account.parentAccountId
        ? String(account.parentAccountId)
        : "",
    });
    setFormVisible(true);
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFormError(null);
    setSuccessMessage(null);

    const accountCode = formState.accountCode.trim();
    const name = formState.name.trim();
    if (!accountCode) {
      setFormError("Account code is required.");
      return;
    }
    if (!name) {
      setFormError("Account name is required.");
      return;
    }

    const parentAccountId = formState.parentAccountId
      ? Number(formState.parentAccountId)
      : null;

    if (editingAccountId && parentAccountId === editingAccountId) {
      setFormError("An account cannot be its own parent.");
      return;
    }

    const payload = {
      accountCode,
      name,
      accountType: formState.accountType,
      status: formState.status,
      parentAccountId,
    };

    try {
      if (editingAccountId) {
        await updateMutation.mutateAsync({
          accountId: editingAccountId,
          payload,
        });
      } else {
        await createMutation.mutateAsync(payload);
      }
      resetForm();
    } catch (error) {
      setFormError(getErrorMessage(error));
    }
  };

  const handleToggleStatus = async (account: Account) => {
    setFormError(null);
    setSuccessMessage(null);
    try {
      if (account.status === "ACTIVE") {
        await deactivateMutation.mutateAsync(account.id);
      } else {
        await updateMutation.mutateAsync({
          accountId: account.id,
          payload: {
            status: "ACTIVE",
          },
        });
      }
    } catch (error) {
      setFormError(getErrorMessage(error));
    }
  };

  const isMutating =
    createMutation.isPending ||
    updateMutation.isPending ||
    deactivateMutation.isPending;

  if (isLoading) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-500">
        Loading accounts…
      </div>
    );
  }

  if (isError) {
    return (
      <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-sm text-red-700">
        Failed to load accounts: {getErrorMessage(error)}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="page-card flex items-center justify-between p-5">
        <div>
          <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
            Chart of accounts
          </p>
          <h2 className="mt-1 text-2xl font-semibold text-slate-900">
            Accounts
          </h2>
        </div>

        {canManageAccounts ? (
          <button
            type="button"
            onClick={openCreateForm}
            className="btn-primary"
          >
            <Plus className="h-4 w-4" />
            New account
          </button>
        ) : null}
      </div>

      {formVisible && canManageAccounts ? (
        <form onSubmit={handleSubmit} className="page-card space-y-4 p-5">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold text-slate-900">
              {editingAccountId ? "Edit account" : "Create account"}
            </h3>
            <button
              type="button"
              onClick={resetForm}
              className="rounded-lg border border-slate-200 p-2 text-slate-500 hover:bg-slate-100"
              aria-label="Close account form"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="grid gap-4 md:grid-cols-2">
            <label className="space-y-1 text-sm text-slate-700">
              <span>Account code</span>
              <input
                value={formState.accountCode}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    accountCode: event.target.value,
                  }))
                }
                className="input-control"
                required
              />
            </label>
            <label className="space-y-1 text-sm text-slate-700">
              <span>Account name</span>
              <input
                value={formState.name}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    name: event.target.value,
                  }))
                }
                className="input-control"
                required
              />
            </label>
            <label className="space-y-1 text-sm text-slate-700">
              <span>Account type</span>
              <select
                value={formState.accountType}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    accountType: event.target.value as Account["accountType"],
                    parentAccountId: "",
                  }))
                }
                className="input-control"
              >
                {accountTypeOptions.map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm text-slate-700">
              <span>Parent account</span>
              <select
                value={formState.parentAccountId}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    parentAccountId: event.target.value,
                  }))
                }
                className="input-control"
              >
                <option value="">No parent</option>
                {accounts
                  .filter(
                    (account) =>
                      account.accountType === formState.accountType &&
                      account.id !== editingAccountId,
                  )
                  .map((account) => (
                    <option key={account.id} value={String(account.id)}>
                      {account.accountCode} - {account.name}
                    </option>
                  ))}
              </select>
            </label>
            <label className="space-y-1 text-sm text-slate-700 md:col-span-2">
              <span>Status</span>
              <select
                value={formState.status}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    status: event.target.value as Account["status"],
                  }))
                }
                className="input-control"
              >
                <option value="ACTIVE">ACTIVE</option>
                <option value="INACTIVE">INACTIVE</option>
              </select>
            </label>
          </div>

          {formError ? (
            <p className="feedback-error" role="alert">
              {formError}
            </p>
          ) : null}

          <div className="flex items-center gap-3">
            <button type="submit" disabled={isMutating} className="btn-primary">
              {editingAccountId ? "Save account" : "Create account"}
            </button>
            <button type="button" onClick={resetForm} className="btn-secondary">
              Cancel
            </button>
          </div>
        </form>
      ) : null}

      {successMessage ? (
        <div className="feedback-success" role="status">
          {successMessage}
        </div>
      ) : null}

      {formError && !formVisible ? (
        <div className="feedback-error" role="alert">
          {formError}
        </div>
      ) : null}

      {accounts.length === 0 ? (
        <div className="page-card p-6 text-sm text-slate-500">
          No accounts found.
        </div>
      ) : (
        <div className="table-wrap">
          <table className="min-w-full text-left text-sm">
            <thead className="table-head">
              <tr>
                <th className="px-4 py-3 font-medium">Code</th>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Type</th>
                <th className="px-4 py-3 font-medium">Parent</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 text-right font-medium">Balance</th>
                {canManageAccounts ? (
                  <th className="px-4 py-3 text-right font-medium">Actions</th>
                ) : null}
              </tr>
            </thead>
            <tbody>
              {accounts.map((account) => (
                <tr key={account.id} className="border-t border-slate-200">
                  <td className="px-4 py-3 font-medium text-slate-800">
                    {account.accountCode}
                  </td>
                  <td className="px-4 py-3 text-slate-700">{account.name}</td>
                  <td className="px-4 py-3 text-slate-600">
                    {account.accountType}
                  </td>
                  <td className="px-4 py-3 text-slate-600">
                    {account.parentAccountId
                      ? (accountById.get(account.parentAccountId)?.name ??
                        "Unknown")
                      : "—"}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`status-badge ${
                        account.status === "ACTIVE"
                          ? "status-active"
                          : "status-inactive"
                      }`}
                    >
                      {account.status}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right font-medium tabular-nums text-slate-900">
                    {formatMoney(balanceByAccountId.get(account.id) ?? "0.00")}
                  </td>
                  {canManageAccounts ? (
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-2">
                        <button
                          type="button"
                          onClick={() => openEditForm(account)}
                          className="btn-secondary p-2"
                          aria-label={`Edit ${account.name}`}
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => handleToggleStatus(account)}
                          className="btn-secondary p-2"
                          aria-label={`Toggle ${account.name} status`}
                          disabled={isMutating}
                        >
                          <Power className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
