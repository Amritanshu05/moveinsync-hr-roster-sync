package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByTenantIdAndUsername(UUID tenantId, String username);
    Optional<User> findByUsername(String username);
}
