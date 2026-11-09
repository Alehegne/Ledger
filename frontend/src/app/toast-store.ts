import { createContext, useContext } from "react";

export type ToastVariant = "success" | "error" | "info";

export interface ToastInput {
  variant: ToastVariant;
  title?: string;
  message: string;
  code?: string;
  durationMs?: number;
}

export interface ToastContextValue {
  showToast: (toast: ToastInput) => void;
  showError: (error: unknown, fallbackMessage?: string) => void;
  showSuccess: (message: string, title?: string) => void;
  dismissToast: (id: string) => void;
}

const noopToastContext: ToastContextValue = {
  showToast: () => {},
  showError: () => {},
  showSuccess: () => {},
  dismissToast: () => {},
};

export const ToastContext = createContext<ToastContextValue>(noopToastContext);

export const useToast = () => useContext(ToastContext);
