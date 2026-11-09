// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ErrorBoundary } from "./error-boundary";

const CrashableChild = ({ shouldCrash }: { shouldCrash: boolean }) => {
  if (shouldCrash) {
    throw new Error("Simulated ledger component crash");
  }
  return <p>Healthy ledger view</p>;
};

const BoundaryHarness = () => {
  const [crashed, setCrashed] = useState(true);

  return (
    <div>
      <button type="button" onClick={() => setCrashed(false)}>
        Fix Child State
      </button>
      <ErrorBoundary>
        <CrashableChild shouldCrash={crashed} />
      </ErrorBoundary>
    </div>
  );
};

describe("ErrorBoundary", () => {
  it("catches unexpected render errors and allows recovery", async () => {
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const user = userEvent.setup();

    try {
      render(<BoundaryHarness />);

      expect(
        screen.getByText("Unable to render this view"),
      ).toBeInTheDocument();
      expect(
        screen.getByText("Simulated ledger component crash"),
      ).toBeInTheDocument();

      await user.click(screen.getByRole("button", { name: "Fix Child State" }));
      await user.click(screen.getByRole("button", { name: "Try again" }));

      expect(screen.getByText("Healthy ledger view")).toBeInTheDocument();
    } finally {
      consoleSpy.mockRestore();
    }
  });
});
