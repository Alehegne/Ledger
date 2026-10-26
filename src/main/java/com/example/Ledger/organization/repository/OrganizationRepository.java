package com.example.Ledger.organization.repository;

import com.example.Ledger.organization.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findByNameIgnoreCase(String name);

    List<Organization> findAllByNameIgnoreCase(String name);
}