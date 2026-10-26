package com.example.Ledger.dashboard.controller;

import com.example.Ledger.dashboard.dto.DashboardOverviewResponse;
import com.example.Ledger.dashboard.service.DashboardOverviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardOverviewController {

    private final DashboardOverviewService dashboardOverviewService;

    public DashboardOverviewController(DashboardOverviewService dashboardOverviewService) {
        this.dashboardOverviewService = dashboardOverviewService;
    }

    @GetMapping("/api/dashboard/overview")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','VIEWER')")
    public DashboardOverviewResponse getOverview() {
        return dashboardOverviewService.getOverview();
    }
}
