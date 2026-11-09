import { type FormEvent, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Decimal from "decimal.js";
import { Eye, Pencil, Plus, Trash2 } from "lucide-react";
import { useAuth } from "../../app/auth-context";
import { useToast } from "../../app/toast-store";
import { formatDateOnly, formatTimestamp, todayISO } from "../../lib/date";
import { getErrorMessage } from "../../lib/errors";
import { formatMoney } from "../../lib/money";
import { useLedgerService } from "../../services/interfaces/service-context";
import type { EntrySide, JournalEntry } from "../../types/domain";

type DraftLineState = {
  rowId: string;
  accountId: string;
  side: EntrySide;
  amount: string;
};

type DraftFormState = {
  transactionDate: string;
  description: string;
  reference: string;
  lines: DraftLineState[];
};

const nextRowId = () =>
  typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random()}`;

const createDefaultLine = (): DraftLineState => ({
  rowId: nextRowId(),
  accountId: "",
  side: "DEBIT",
  amount: "",
});

const defaultDraftFormState = (): DraftFormState => ({
  transactionDate: todayISO(),
  description: "",
  reference: "",
  lines: [createDefaultLine(), { ...createDefaultLine(), side: "CREDIT" }],
});

const parseAmount = (value: string) => {
  try {
    const parsed = new Decimal(value || 0);
    return parsed.isFinite() ? parsed : new Decimal(0);
  } catch {
    return new Decimal(0);
  }
};

export const JournalPage = () => {
  const service = useLedgerService();
  const queryClient = useQueryClient();
  const { session } = useAuth();
  const { showSuccess } = useToast();
  const currency = session?.user.baseCurrency ?? "USD";
  const canCreateJournal = session?.user.role !== "VIEWER";
  const [statusFilter, setStatusFilter] = useState<"ALL" | "DRAFT" | "POSTED">(
    "ALL",
  );
  const [page, setPage] = useState(1);
  const [size] = useState(10);
  const [selectedEntryId, setSelectedEntryId] = useState<number | null>(null);
  const [editingEntryId, setEditingEntryId] = useState<number | null>(null);
  const [formVisible, setFormVisible] = useState(false);
  const [formState, setFormState] = useState<DraftFormState>(
    defaultDraftFormState,
  );
  const [feedbackError, setFeedbackError] = useState<string | null>(null);
  const [feedbackSuccess, setFeedbackSuccess] = useState<string | null>(null);
  const [reversalModalEntry, setReversalModalEntry] = useState<JournalEntry | null>(null);
  const [reversalReason, setReversalReason] = useState("");

  const journalQueryInput = useMemo(
    () => ({
      page,
      size,
      status: statusFilter === "ALL" ? undefined : statusFilter,
    }),
    [page, size, statusFilter],
  );

  const { data, isLoading } = useQuery({
    queryKey: ["journalEntries", journalQueryInput],
    queryFn: () => service.listJournalEntries(journalQueryInput),
  });

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: () => service.listAccounts(),
  });

  const { data: selectedEntry, isLoading: selectedEntryLoading } = useQuery({
    queryKey: ["journalEntry", selectedEntryId],
    queryFn: () => service.getJournalEntry(selectedEntryId as number),
    enabled: selectedEntryId !== null,
  });

  const invalidateAccountingViews = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ["journalEntries"] }),
      queryClient.invalidateQueries({ queryKey: ["journalEntry"] }),
      queryClient.invalidateQueries({ queryKey: ["overview"] }),
      queryClient.invalidateQueries({ queryKey: ["trialBalance"] }),
      queryClient.invalidateQueries({ queryKey: ["generalLedger"] }),
    ]);
  };

  const createDraftMutation = useMutation({
    mutationFn: () =>
      service.createJournalDraft({
        transactionDate: formState.transactionDate,
        description: formState.description,
        reference: formState.reference.trim() || undefined,
        lines: formState.lines.map((line) => ({
          accountId: Number(line.accountId),
          side: line.side,
          amount: line.amount,
        })),
      }),
  });

  const updateDraftMutation = useMutation({
    mutationFn: (entryId: number) =>
      service.updateJournalDraft(entryId, {
        transactionDate: formState.transactionDate,
        description: formState.description,
        reference: formState.reference.trim() || undefined,
        lines: formState.lines.map((line) => ({
          accountId: Number(line.accountId),
          side: line.side,
          amount: line.amount,
        })),
      }),
  });

  const deleteDraftMutation = useMutation({
    mutationFn: (entryId: number) => service.deleteDraft(entryId),
  });

  const postMutation = useMutation({
    mutationFn: (entryId: number) => service.postJournalEntry(entryId),
  });

  const reverseMutation = useMutation({
    mutationFn: ({ entryId, reason }: { entryId: number; reason?: string }) =>
      service.reverseJournalEntry(entryId, { reason }),
  });

  const activeAccounts = useMemo(
    () => accounts.filter((account) => account.status === "ACTIVE"),
    [accounts],
  );

  const totals = useMemo(() => {
    const debit = formState.lines.reduce((sum, line) => {
      if (line.side !== "DEBIT") return sum;
      return sum.plus(parseAmount(line.amount));
    }, new Decimal(0));

    const credit = formState.lines.reduce((sum, line) => {
      if (line.side !== "CREDIT") return sum;
      return sum.plus(parseAmount(line.amount));
    }, new Decimal(0));

    return {
      debit,
      credit,
      difference: debit.minus(credit),
    };
  }, [formState.lines]);

  const openCreateForm = () => {
    setFeedbackError(null);
    setFeedbackSuccess(null);
    setEditingEntryId(null);
    setFormState(defaultDraftFormState());
    setFormVisible(true);
  };

  const openEditForm = (entry: JournalEntry) => {
    setFeedbackError(null);
    setFeedbackSuccess(null);
    setEditingEntryId(entry.id);
    setFormState({
      transactionDate: entry.transactionDate,
      description: entry.description,
      reference: entry.reference ?? "",
      lines: entry.lines.map((line) => ({
        rowId: nextRowId(),
        accountId: String(line.accountId),
        side: line.side,
        amount: line.amount,
      })),
    });
    setFormVisible(true);
  };

  const closeForm = () => {
    setFormVisible(false);
    setEditingEntryId(null);
    setFormState(defaultDraftFormState());
    setFeedbackError(null);
  };

  const updateLine = (
    rowId: string,
    patch: Partial<Pick<DraftLineState, "accountId" | "side" | "amount">>,
  ) => {
    setFormState((prev) => ({
      ...prev,
      lines: prev.lines.map((line) =>
        line.rowId === rowId ? { ...line, ...patch } : line,
      ),
    }));
  };

  const addLine = () => {
    setFormState((prev) => ({
      ...prev,
      lines: [...prev.lines, createDefaultLine()],
    }));
  };

  const removeLine = (rowId: string) => {
    setFormState((prev) => {
      if (prev.lines.length <= 2) {
        return prev;
      }
      return {
        ...prev,
        lines: prev.lines.filter((line) => line.rowId !== rowId),
      };
    });
  };

  const validateDraftForm = (): string | null => {
    if (!formState.transactionDate) {
      return "Transaction date is required.";
    }
    if (!formState.description.trim()) {
      return "Description is required.";
    }
    if (formState.lines.length < 2) {
      return "At least two lines are required.";
    }

    for (const [index, line] of formState.lines.entries()) {
      if (!line.accountId) {
        return `Line ${index + 1} requires an account.`;
      }
      const amount = parseAmount(line.amount);
      if (!amount.isFinite() || amount.lte(0)) {
        return `Line ${index + 1} must have a positive amount.`;
      }
    }

    if (!totals.difference.eq(0)) {
      return "Journal entry is not balanced. Debits and credits must match.";
    }

    return null;
  };

  const handleSaveDraft = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFeedbackError(null);
    setFeedbackSuccess(null);

    const validationError = validateDraftForm();
    if (validationError) {
      setFeedbackError(validationError);
      return;
    }

    try {
      const result = editingEntryId
        ? await updateDraftMutation.mutateAsync(editingEntryId)
        : await createDraftMutation.mutateAsync();

      await invalidateAccountingViews();
      setSelectedEntryId(result.id);
      const message = editingEntryId
        ? "Draft updated successfully."
        : "Draft created successfully.";
      setFeedbackSuccess(message);
      showSuccess(message);
      closeForm();
    } catch (error) {
      setFeedbackError(getErrorMessage(error));
    }
  };

  const handleDeleteDraft = async (entryId: number) => {
    setFeedbackError(null);
    setFeedbackSuccess(null);
    const confirmed = window.confirm("Delete this draft entry?");
    if (!confirmed) return;

    try {
      await deleteDraftMutation.mutateAsync(entryId);
      await invalidateAccountingViews();
      if (selectedEntryId === entryId) {
        setSelectedEntryId(null);
      }
      setFeedbackSuccess("Draft deleted.");
      showSuccess("Draft deleted.");
    } catch (error) {
      setFeedbackError(getErrorMessage(error));
    }
  };

  const handlePostDraft = async (entryId: number) => {
    setFeedbackError(null);
    setFeedbackSuccess(null);
    try {
      await postMutation.mutateAsync(entryId);
      await invalidateAccountingViews();
      setFeedbackSuccess("Draft posted successfully.");
      showSuccess("Draft posted successfully.");
    } catch (error) {
      setFeedbackError(getErrorMessage(error));
    }
  };

  const handleOpenReverseModal = (entry: JournalEntry) => {
    setFeedbackError(null);
    setFeedbackSuccess(null);
    setReversalModalEntry(entry);
    setReversalReason("");
  };

  const handleConfirmReverse = async (e: FormEvent) => {
    e.preventDefault();
    if (!reversalModalEntry) return;
    setFeedbackError(null);
    setFeedbackSuccess(null);
    try {
      const reversed = await reverseMutation.mutateAsync({
        entryId: reversalModalEntry.id,
        reason: reversalReason.trim() || undefined,
      });
      await invalidateAccountingViews();
      const message = `Journal entry JE-${reversalModalEntry.id} successfully reversed with entry JE-${reversed.id}.`;
      setFeedbackSuccess(message);
      showSuccess(message);
      setReversalModalEntry(null);
    } catch (error) {
      setFeedbackError(getErrorMessage(error));
    }
  };

  const isMutating =
    createDraftMutation.isPending ||
    updateDraftMutation.isPending ||
    deleteDraftMutation.isPending ||
    postMutation.isPending ||
    reverseMutation.isPending;

  if (isLoading || !data) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-500">
        Loading journal entries…
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="page-card flex items-center justify-between p-5">
        <div>
          <p className="text-xs uppercase tracking-[0.2em] text-slate-500">
            Transactions
          </p>
          <h2 className="mt-1 text-2xl font-semibold text-slate-900">
            Journal
          </h2>
        </div>

        {canCreateJournal ? (
          <button
            type="button"
            onClick={openCreateForm}
            className="btn-primary"
          >
            <Plus className="h-4 w-4" />
            New draft
          </button>
        ) : null}
      </div>

      <div className="page-card p-4">
        <label className="text-sm text-slate-700">
          Filter status
          <select
            value={statusFilter}
            onChange={(event) => {
              setStatusFilter(event.target.value as "ALL" | "DRAFT" | "POSTED");
              setPage(1);
            }}
            className="input-control ml-3 inline-flex h-9 w-auto min-w-32 px-3 py-1.5"
          >
            <option value="ALL">All</option>
            <option value="DRAFT">Draft</option>
            <option value="POSTED">Posted</option>
          </select>
        </label>
      </div>

      {formVisible && canCreateJournal ? (
        <form onSubmit={handleSaveDraft} className="page-card space-y-4 p-5">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-semibold text-slate-900">
              {editingEntryId ? "Edit draft" : "Create draft"}
            </h3>
            <button type="button" onClick={closeForm} className="btn-secondary">
              Cancel
            </button>
          </div>

          <div className="grid gap-4 md:grid-cols-3">
            <label className="space-y-1 text-sm text-slate-700">
              <span>Date</span>
              <input
                type="date"
                value={formState.transactionDate}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    transactionDate: event.target.value,
                  }))
                }
                className="input-control"
                required
              />
            </label>
            <label className="space-y-1 text-sm text-slate-700 md:col-span-2">
              <span>Description</span>
              <input
                value={formState.description}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    description: event.target.value,
                  }))
                }
                className="input-control"
                required
              />
            </label>
            <label className="space-y-1 text-sm text-slate-700 md:col-span-3">
              <span>Reference (optional)</span>
              <input
                value={formState.reference}
                onChange={(event) =>
                  setFormState((prev) => ({
                    ...prev,
                    reference: event.target.value,
                  }))
                }
                className="input-control"
              />
            </label>
          </div>

          <div className="space-y-3">
            {formState.lines.map((line, index) => (
              <div
                key={line.rowId}
                className="grid gap-3 rounded-xl border border-slate-200 p-3 md:grid-cols-12"
              >
                <label className="space-y-1 text-sm text-slate-700 md:col-span-5">
                  <span>Account {index + 1}</span>
                  <select
                    value={line.accountId}
                    onChange={(event) =>
                      updateLine(line.rowId, { accountId: event.target.value })
                    }
                    className="input-control"
                    required
                  >
                    <option value="">Select account</option>
                    {activeAccounts.map((account) => (
                      <option key={account.id} value={String(account.id)}>
                        {account.accountCode} - {account.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="space-y-1 text-sm text-slate-700 md:col-span-3">
                  <span>Side</span>
                  <select
                    value={line.side}
                    onChange={(event) =>
                      updateLine(line.rowId, {
                        side: event.target.value as EntrySide,
                      })
                    }
                    className="input-control"
                  >
                    <option value="DEBIT">DEBIT</option>
                    <option value="CREDIT">CREDIT</option>
                  </select>
                </label>
                <label className="space-y-1 text-sm text-slate-700 md:col-span-3">
                  <span>Amount</span>
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    value={line.amount}
                    onChange={(event) =>
                      updateLine(line.rowId, { amount: event.target.value })
                    }
                    className="input-control"
                    required
                  />
                </label>
                <div className="flex items-end md:col-span-1">
                  <button
                    type="button"
                    onClick={() => removeLine(line.rowId)}
                    disabled={formState.lines.length <= 2}
                    className="btn-secondary w-full p-2"
                    aria-label="Remove line"
                  >
                    <Trash2 className="mx-auto h-4 w-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>

          <div className="flex flex-wrap items-center gap-3 text-sm">
            <button type="button" onClick={addLine} className="btn-secondary">
              Add line
            </button>
            <span className="rounded-full bg-slate-100 px-3 py-1 text-slate-700">
              Debit {formatMoney(totals.debit.toFixed(2), currency)}
            </span>
            <span className="rounded-full bg-slate-100 px-3 py-1 text-slate-700">
              Credit {formatMoney(totals.credit.toFixed(2), currency)}
            </span>
            <span
              className={`rounded-full px-3 py-1 ${totals.difference.eq(0) ? "bg-emerald-100 text-emerald-700" : "bg-red-100 text-red-700"}`}
            >
              Difference {formatMoney(totals.difference.abs().toFixed(2), currency)}
            </span>
          </div>

          {feedbackError ? (
            <p className="feedback-error" role="alert">
              {feedbackError}
            </p>
          ) : null}

          <button type="submit" disabled={isMutating} className="btn-primary">
            {editingEntryId ? "Save draft" : "Create draft"}
          </button>
        </form>
      ) : null}

      {feedbackSuccess ? (
        <div className="feedback-success" role="status">
          {feedbackSuccess}
        </div>
      ) : null}

      {feedbackError && !formVisible ? (
        <div className="feedback-error" role="alert">
          {feedbackError}
        </div>
      ) : null}

      <div className="table-wrap">
        <table className="min-w-full text-left text-sm">
          <thead className="table-head">
            <tr>
              <th className="px-4 py-3 font-medium">Description</th>
              <th className="px-4 py-3 font-medium">Date</th>
              <th className="px-4 py-3 font-medium">Reference</th>
              <th className="px-4 py-3 font-medium">Post status</th>
              <th className="px-4 py-3 text-right font-medium">Debits</th>
              <th className="px-4 py-3 text-right font-medium">Credits</th>
              <th className="px-4 py-3 text-right font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((entry) => (
              <tr key={entry.id} className="border-t border-slate-200">
                <td className="px-4 py-3 font-medium text-slate-800">
                  {entry.description}
                </td>
                <td className="px-4 py-3 text-slate-600">
                  {formatDateOnly(entry.transactionDate)}
                </td>
                <td className="px-4 py-3 text-slate-600">
                  {entry.reference ?? "—"}
                </td>
                <td className="px-4 py-3">
                  {entry.status === "DRAFT" ? (
                    <span className="status-badge status-draft">DRAFT</span>
                  ) : entry.reversedByEntryId ? (
                    <span
                      className="status-badge bg-amber-100 text-amber-800"
                      title={`Reversed by Entry #${entry.reversedByEntryId}`}
                    >
                      REVERSED
                    </span>
                  ) : entry.reversesEntryId ? (
                    <span
                      className="status-badge bg-indigo-100 text-indigo-800"
                      title={`Reversal of Entry #${entry.reversesEntryId}`}
                    >
                      REVERSAL
                    </span>
                  ) : (
                    <span className="status-badge status-posted">POSTED</span>
                  )}
                </td>
                <td className="px-4 py-3 text-right font-medium tabular-nums text-slate-900">
                  {formatMoney(entry.totalDebits, currency)}
                </td>
                <td className="px-4 py-3 text-right font-medium tabular-nums text-slate-900">
                  {formatMoney(entry.totalCredits, currency)}
                </td>
                <td className="px-4 py-3">
                  <div className="flex justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => setSelectedEntryId(entry.id)}
                      className="btn-secondary p-2"
                      aria-label={`View ${entry.description}`}
                    >
                      <Eye className="h-4 w-4" />
                    </button>
                    {canCreateJournal && entry.status === "DRAFT" ? (
                      <>
                        <button
                          type="button"
                          onClick={() => openEditForm(entry)}
                          className="btn-secondary p-2"
                          aria-label={`Edit ${entry.description}`}
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => handlePostDraft(entry.id)}
                          className="btn-secondary border-emerald-300 px-2 py-1 text-xs text-emerald-700"
                          disabled={isMutating}
                        >
                          Post
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDeleteDraft(entry.id)}
                          className="btn-danger p-2"
                          aria-label={`Delete ${entry.description}`}
                          disabled={isMutating}
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </>
                    ) : null}
                    {canCreateJournal &&
                    entry.status === "POSTED" &&
                    !entry.reversedByEntryId &&
                    !entry.reversesEntryId ? (
                      <button
                        type="button"
                        onClick={() => handleOpenReverseModal(entry)}
                        className="btn-secondary border-amber-300 px-2 py-1 text-xs text-amber-700 hover:bg-amber-50"
                        disabled={isMutating}
                      >
                        Reverse
                      </button>
                    ) : null}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="page-card flex items-center justify-between px-4 py-3 text-sm text-slate-700">
        <span>
          Page {data.page} of {data.totalPages}
        </span>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setPage((value) => Math.max(1, value - 1))}
            disabled={data.page <= 1}
            className="btn-secondary"
          >
            Previous
          </button>
          <button
            type="button"
            onClick={() =>
              setPage((value) => Math.min(data.totalPages, value + 1))
            }
            disabled={data.page >= data.totalPages}
            className="btn-secondary"
          >
            Next
          </button>
        </div>
      </div>

      {selectedEntryId ? (
        <section className="page-card p-5">
          <h3 className="mb-3 text-lg font-semibold text-slate-900">
            Entry details
          </h3>
          {selectedEntryLoading || !selectedEntry ? (
            <p className="text-sm text-slate-500">Loading entry details…</p>
          ) : (
            <div className="space-y-4">
              <div className="grid gap-3 text-sm md:grid-cols-3">
                <div>
                  <p className="text-slate-500">Status</p>
                  <p className="font-medium text-slate-800">
                    {selectedEntry.status}
                  </p>
                </div>
                <div>
                  <p className="text-slate-500">Posted at</p>
                  <p className="font-medium text-slate-800">
                    {selectedEntry.postedAt
                      ? formatTimestamp(selectedEntry.postedAt)
                      : "Not posted"}
                  </p>
                </div>
                <div>
                  <p className="text-slate-500">Reference</p>
                  <p className="font-medium text-slate-800">
                    {selectedEntry.reference ?? "—"}
                  </p>
                </div>
                {selectedEntry.reversedByEntryId ? (
                  <div>
                    <p className="text-slate-500">Reversed by</p>
                    <button
                      type="button"
                      onClick={() =>
                        setSelectedEntryId(selectedEntry.reversedByEntryId!)
                      }
                      className="text-xs font-semibold text-amber-700 underline hover:text-amber-900"
                    >
                      Entry #{selectedEntry.reversedByEntryId}
                    </button>
                    {selectedEntry.reversalReason ? (
                      <p className="mt-0.5 text-xs text-slate-500">
                        {selectedEntry.reversalReason}
                      </p>
                    ) : null}
                  </div>
                ) : null}
                {selectedEntry.reversesEntryId ? (
                  <div>
                    <p className="text-slate-500">Reversal of</p>
                    <button
                      type="button"
                      onClick={() =>
                        setSelectedEntryId(selectedEntry.reversesEntryId!)
                      }
                      className="text-xs font-semibold text-indigo-700 underline hover:text-indigo-900"
                    >
                      Entry #{selectedEntry.reversesEntryId}
                    </button>
                    {selectedEntry.reversalReason ? (
                      <p className="mt-0.5 text-xs text-slate-500">
                        {selectedEntry.reversalReason}
                      </p>
                    ) : null}
                  </div>
                ) : null}
              </div>

              <div className="overflow-x-auto rounded-xl border border-slate-200">
                <table className="min-w-full text-left text-sm">
                  <thead className="bg-slate-50 text-slate-600">
                    <tr>
                      <th className="px-3 py-2 font-medium">Account</th>
                      <th className="px-3 py-2 font-medium">Side</th>
                      <th className="px-3 py-2 text-right font-medium">
                        Amount
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedEntry.lines.map((line) => (
                      <tr key={line.id} className="border-t border-slate-200">
                        <td className="px-3 py-2 text-slate-700">
                          {line.accountCode} - {line.accountName}
                        </td>
                        <td className="px-3 py-2 text-slate-700">
                          {line.side}
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums text-slate-900">
                          {formatMoney(line.amount, currency)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>
      ) : null}

      {reversalModalEntry ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl">
            <h3 className="text-lg font-semibold text-slate-900">
              Reverse Journal Entry
            </h3>
            <p className="mt-2 text-sm text-slate-600">
              You are about to reverse entry #{reversalModalEntry.id} (&ldquo;
              {reversalModalEntry.description}&rdquo;). An offsetting entry with
              inverted debits and credits will be automatically posted.
            </p>
            <form onSubmit={handleConfirmReverse} className="mt-4 space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700">
                  Reversal Reason (required)
                </label>
                <input
                  type="text"
                  required
                  maxLength={500}
                  value={reversalReason}
                  onChange={(e) => setReversalReason(e.target.value)}
                  placeholder="e.g. Invoice input error, duplicate entry"
                  className="input-control mt-1 w-full"
                  autoFocus
                />
              </div>
              <div className="flex justify-end gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setReversalModalEntry(null)}
                  className="btn-secondary"
                  disabled={reverseMutation.isPending}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-danger"
                  disabled={reverseMutation.isPending || !reversalReason.trim()}
                >
                  {reverseMutation.isPending
                    ? "Reversing..."
                    : "Confirm Reversal"}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}
    </div>
  );
};
