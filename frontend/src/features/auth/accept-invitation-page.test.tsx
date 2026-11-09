// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AcceptInvitationPage } from "./accept-invitation-page";
import { ServiceContext } from "../../services/interfaces/service-context";
import { createMockService } from "../../services/mock/mock-service";

describe("accept invitation page", () => {
  it("pre-fills email and token from URL query parameters", () => {
    const service = createMockService(null);

    render(
      <ServiceContext.Provider value={service}>
        <MemoryRouter initialEntries={["/accept-invitation?token=test_token_123&email=newuser@test.co"]}>
          <AcceptInvitationPage />
        </MemoryRouter>
      </ServiceContext.Provider>,
    );

    const emailInput = screen.getByPlaceholderText("name@example.com") as HTMLInputElement;
    const tokenInput = screen.getByPlaceholderText("Paste invitation token") as HTMLInputElement;

    expect(emailInput.value).toBe("newuser@test.co");
    expect(tokenInput.value).toBe("test_token_123");
  });

  it("validates password length and match before submitting", async () => {
    const user = userEvent.setup();
    const service = createMockService(null);

    render(
      <ServiceContext.Provider value={service}>
        <MemoryRouter initialEntries={["/accept-invitation?token=tok123&email=user@test.co"]}>
          <AcceptInvitationPage />
        </MemoryRouter>
      </ServiceContext.Provider>,
    );

    await user.type(screen.getByPlaceholderText("Alex Morgan"), "Alice Morgan");
    await user.type(screen.getByPlaceholderText("At least 8 characters"), "short");
    await user.type(screen.getByPlaceholderText("Re-enter password"), "short");

    await user.click(screen.getByRole("button", { name: "Complete account setup" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("at least 8 characters");

    // Fix length but mismatch
    await user.clear(screen.getByPlaceholderText("At least 8 characters"));
    await user.type(screen.getByPlaceholderText("At least 8 characters"), "Password123!");
    await user.clear(screen.getByPlaceholderText("Re-enter password"));
    await user.type(screen.getByPlaceholderText("Re-enter password"), "Different123!");

    await user.click(screen.getByRole("button", { name: "Complete account setup" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Passwords do not match");
  });

  it("submits valid credentials and shows success activation state", async () => {
    const user = userEvent.setup();
    const acceptInvitationMock = vi.fn(async () => ({ message: "Success" }));
    const service = {
      ...createMockService(null),
      acceptInvitation: acceptInvitationMock,
    };

    render(
      <ServiceContext.Provider value={service}>
        <MemoryRouter initialEntries={["/accept-invitation?token=tok123&email=user@test.co"]}>
          <AcceptInvitationPage />
        </MemoryRouter>
      </ServiceContext.Provider>,
    );

    await user.type(screen.getByPlaceholderText("Alex Morgan"), "Alice Morgan");
    await user.type(screen.getByPlaceholderText("At least 8 characters"), "Password123!");
    await user.type(screen.getByPlaceholderText("Re-enter password"), "Password123!");

    await user.click(screen.getByRole("button", { name: "Complete account setup" }));

    expect(acceptInvitationMock).toHaveBeenCalledWith({
      token: "tok123",
      email: "user@test.co",
      fullName: "Alice Morgan",
      password: "Password123!",
    });

    expect(await screen.findByRole("heading", { name: "Account activated!" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go to sign in" })).toHaveAttribute("href", "/login");
  });
});
