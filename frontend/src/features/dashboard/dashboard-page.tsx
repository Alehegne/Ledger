import { useQuery } from "@tanstack/react-query";
import {
  CircleDollarSign,
  FileText,
  PiggyBank,
  TrendingUp,
} from "lucide-react";
import { UnsupportedInApiModeError } from "../../lib/errors";
import { formatMoney } from "../../lib/money";
import { useLedgerService } from "../../services/interfaces/service-context";

const toUnavailableMessage = (error: unknown, fallback: string) => {
  if (error instanceof UnsupportedInApiModeError) {
    return fallback;
  }

  const message = error instanceof Error ? error.message : "";
  if (/not available|not supported|not enabled/i.test(message)) {
    return fallback;
  }

  return message || "Dashboard data is unavailable.";
};

export const DashboardPage = () => {
  const service = useLedgerService();

  const {
    data: overview,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ["overview"],
    queryFn: () => service.getOverview(),
    retry: false,
  });

  if (isLoading) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-500">
        Loading dashboard…
      </div>
    );
  }

  if (isError || !overview) {
    const message = toUnavailableMessage(
      error,
      "Dashboard data is not available in the current API contract.",
    );

    return (
      <div className="rounded-2xl border border-amber-200 bg-amber-50 p-6 text-sm text-amber-900">
        <p className="font-medium">Dashboard unavailable</p>
        <p className="mt-2">{message}</p>
        <button
          type="button"
          onClick={() => void refetch()}
          className="mt-3 rounded-md border border-amber-300 bg-white px-3 py-1.5 font-medium text-amber-900 transition hover:bg-amber-100"
        >
          Retry
        </button>
      </div>
    );
  }

  const cards = [
    {
      label: "Total assets",
      value: formatMoney(overview.totalAssets),
      icon: PiggyBank,
    },
    {
      label: "Revenue",
      value: formatMoney(overview.totalRevenue),
      icon: TrendingUp,
    },
    {
      label: "Expenses",
      value: formatMoney(overview.totalExpenses),
      icon: CircleDollarSign,
    },
    {
      label: "Open entries",
      value: String(overview.draftsCount),
      icon: FileText,
    },
  ];

  return (
    <div className="space-y-6">
      <section className="page-card p-5">
        <p className="page-header-kicker">Overview</p>
        <h2 className="mt-1 text-xl font-semibold text-slate-900">
          Financial snapshot
        </h2>
        <p className="mt-1 text-sm text-slate-600">
          Metrics are based on posted accounting activity as of{" "}
          {new Date(overview.asOfDate).toLocaleDateString()}.
        </p>
      </section>

      <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        {cards.map(({ label, value, icon: Icon }) => (
          <div key={label} className="page-card p-5">
            <div className="flex items-center justify-between">
              <p className="text-sm text-slate-500">{label}</p>
              <span className="rounded-lg bg-slate-100 p-2 text-slate-700">
                <Icon className="h-4 w-4" />
              </span>
            </div>
            <p className="mt-6 text-2xl font-semibold text-slate-900">
              {value}
            </p>
          </div>
        ))}
      </section>

      <section className="page-card p-5">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-slate-900">
            Recent journal activity
          </h2>
          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600">
            {overview.postedCount} posted
          </span>
        </div>

        {overview.recentEntries.length === 0 ? (
          <div className="page-card-muted p-4 text-sm text-slate-600">
            No journal activity has been posted yet.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-left text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-slate-500">
                  <th className="pb-3 pr-4 font-medium">Description</th>
                  <th className="pb-3 pr-4 font-medium">Date</th>
                  <th className="pb-3 pr-4 font-medium">Reference</th>
                  <th className="pb-3 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {overview.recentEntries.map((entry) => (
                  <tr
                    key={entry.id}
                    className="border-b border-slate-100 last:border-0"
                  >
                    <td className="py-3 pr-4 font-medium text-slate-800">
                      {entry.description}
                    </td>
                    <td className="py-3 pr-4 text-slate-600">
                      {new Date(entry.transactionDate).toLocaleDateString()}
                    </td>
                    <td className="py-3 pr-4 text-slate-600">
                      {entry.reference ?? "—"}
                    </td>
                    <td className="py-3">
                      <span
                        className={`status-badge ${
                          entry.status === "POSTED"
                            ? "status-posted"
                            : "status-draft"
                        }`}
                      >
                        {entry.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
};
