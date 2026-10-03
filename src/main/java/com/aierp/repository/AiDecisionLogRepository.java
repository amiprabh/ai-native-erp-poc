// repository/AiDecisionLogRepository.java
package com.aierp.repository;

import com.aierp.domain.ai.AiDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface AiDecisionLogRepository extends JpaRepository<AiDecisionLog, UUID> {
}
