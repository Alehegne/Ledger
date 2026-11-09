// @vitest-environment jsdom
import { useMutation } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { AppProviders } from "./providers";
import { ToastProvider } from "./toast-provider";
import { useToast } from "./toast-store";
import { AppError } from "../lib/errors";

const ToastHarness = () => {
  const { showError, showSuccess } = useToast();

  return (
    <div>
      <button
        type="button"
        onClick={() =>
          showError(
            new AppError({
              status: 409,
              code: "BASE_CURRENCY_LOCKED",
              message:
                "Base currency cannot be changed after journal transactions exist.",
            }),
          )
        }
      >
        Trigger Currency Lock
      </button>
      <button
        type="button"
        onClick={() =>
          showError(
            new AppError({
              status: 422,
              code: "UNBALANCED_JOURNAL",
              message: "Debits and credits must match.",
            }),
          )
        }
      >
        Trigger Unbalanced
      </button>
      <button
        type="button"
        onClick={() => showSuccess("Journal entry JE-101 posted.")}
      >
        Trigger Success
      </button>
    </div>
  );
};

const MutationErrorHarness = () => {
  const mutation = useMutation({
    mutationFn: async () => {
      throw new AppError({
        status: 422,
        code: "ACCOUNT_INACTIVE",
        message: "Account 1000 is deactivated.",
      });
    },
  });

  return (
    <button type="button" onClick={() => mutation.mutate()}>
      Run Failing Mutation
    </button>
  );
};

describe("ToastProvider and global error interception", () => {
  it("maps known backend error codes to actionable notifications and supports dismissal", async () => {
    const user = userEvent.setup();
    render(
      <ToastProvider>
        <ToastHarness />
      </ToastProvider>,
    );

    await user.click(
      screen.getByRole("button", { name: "Trigger Currency Lock" }),
    );
    expect(screen.getByText("Base Currency Locked")).toBeInTheDocument();
    expect(screen.getByText("BASE_CURRENCY_LOCKED")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Trigger Unbalanced" }));
    expect(screen.getByText("Unbalanced Journal Entry")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Trigger Success" }));
    expect(screen.getByText("Journal entry JE-101 posted.")).toBeInTheDocument();

    const dismissButtons = screen.getAllByRole("button", {
      name: "Dismiss notification",
    });
    await user.click(dismissButtons[0]);
    expect(screen.queryByText("Base Currency Locked")).not.toBeInTheDocument();
  });

  it("intercepts React Query mutation errors globally through AppProviders", async () => {
    const user = userEvent.setup();
    render(
      <AppProviders>
        <MutationErrorHarness />
      </AppProviders>,
    );

    await user.click(
      screen.getByRole("button", { name: "Run Failing Mutation" }),
    );

    expect(
      await screen.findByText("Inactive Account Selected"),
    ).toBeInTheDocument();
    expect(screen.getByText("ACCOUNT_INACTIVE")).toBeInTheDocument();
    expect(
      screen.getByText("Account 1000 is deactivated."),
    ).toBeInTheDocument();
  });
});
