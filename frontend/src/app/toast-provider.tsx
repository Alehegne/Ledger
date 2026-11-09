import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertCircle, CheckCircle2, Info, X } from "lucide-react";
import { formatActionableError } from "../lib/errors";
import { ToastContext, type ToastInput, type ToastVariant } from "./toast-store";

interface ToastItem {
  id: string;
  variant: ToastVariant;
  title?: string;
  message: string;
  code?: string;
}

const DEFAULT_DURATION_MS = 5000;

const variantStyles: Record<
  ToastVariant,
  { container: string; badge: string; iconColor: string }
> = {
  success: {
    container: "border-emerald-200 bg-white text-slate-900 shadow-lg",
    badge: "bg-emerald-100 text-emerald-800",
    iconColor: "text-emerald-600",
  },
  error: {
    container: "border-red-200 bg-white text-slate-900 shadow-lg",
    badge: "bg-red-100 text-red-700",
    iconColor: "text-red-600",
  },
  info: {
    container: "border-slate-200 bg-white text-slate-900 shadow-lg",
    badge: "bg-slate-100 text-slate-700",
    iconColor: "text-slate-600",
  },
};

const VariantIcon = ({ variant }: { variant: ToastVariant }) => {
  const className = `h-5 w-5 shrink-0 ${variantStyles[variant].iconColor}`;
  if (variant === "success") return <CheckCircle2 className={className} />;
  if (variant === "error") return <AlertCircle className={className} />;
  return <Info className={className} />;
};

export const ToastProvider = ({
  children,
}: {
  children: React.ReactNode;
}) => {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(
    new Map(),
  );

  const dismissToast = useCallback((id: string) => {
    const existingTimer = timersRef.current.get(id);
    if (existingTimer) {
      clearTimeout(existingTimer);
      timersRef.current.delete(id);
    }
    setToasts((prev) => prev.filter((item) => item.id !== id));
  }, []);

  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      for (const timer of timers.values()) {
        clearTimeout(timer);
      }
      timers.clear();
    };
  }, []);

  const showToast = useCallback(
    (input: ToastInput) => {
      const id =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random()}`;

      const item: ToastItem = {
        id,
        variant: input.variant,
        title: input.title,
        message: input.message,
        code: input.code,
      };

      setToasts((prev) => [...prev.slice(-4), item]);

      const duration = input.durationMs ?? DEFAULT_DURATION_MS;
      if (duration > 0) {
        const timer = setTimeout(() => {
          dismissToast(id);
        }, duration);
        timersRef.current.set(id, timer);
      }
    },
    [dismissToast],
  );

  const showError = useCallback(
    (error: unknown, fallbackMessage?: string) => {
      const formatted = formatActionableError(error, fallbackMessage);
      showToast({
        variant: "error",
        title: formatted.title,
        message: formatted.message,
        code: formatted.code,
        durationMs: 7000,
      });
    },
    [showToast],
  );

  const showSuccess = useCallback(
    (message: string, title = "Completed") => {
      showToast({
        variant: "success",
        title,
        message,
        durationMs: 4500,
      });
    },
    [showToast],
  );

  const value = useMemo(
    () => ({ showToast, showError, showSuccess, dismissToast }),
    [showToast, showError, showSuccess, dismissToast],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      {toasts.length > 0 ? (
        <div
          role="region"
          aria-label="Notifications"
          className="fixed bottom-4 right-4 z-50 flex w-full max-w-sm flex-col gap-2"
        >
          {toasts.map((toast) => {
            const styles = variantStyles[toast.variant];
            return (
              <div
                key={toast.id}
                role={toast.variant === "error" ? "alert" : "status"}
                className={`flex items-start gap-3 rounded-xl border p-4 ${styles.container}`}
              >
                <VariantIcon variant={toast.variant} />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    {toast.title ? (
                      <p className="text-sm font-semibold text-slate-900">
                        {toast.title}
                      </p>
                    ) : null}
                    {toast.code ? (
                      <span
                        className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ${styles.badge}`}
                      >
                        {toast.code}
                      </span>
                    ) : null}
                  </div>
                  <p className="mt-1 text-xs leading-relaxed text-slate-600">
                    {toast.message}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => dismissToast(toast.id)}
                  aria-label="Dismiss notification"
                  className="rounded-lg p-1 text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            );
          })}
        </div>
      ) : null}
    </ToastContext.Provider>
  );
};
