package com.chaoslab.target.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataTargetJpaRepository extends JpaRepository<TargetJpaEntity, String> {
}
