package com.yoooche.points.repository;

import com.yoooche.points.model.UserPointsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserPointsRecordRepository extends JpaRepository<UserPointsRecord, Long> {

    @Transactional(readOnly = true)
    Optional<UserPointsRecord> findByIdempotencyKey(String idempotencyKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserPointsRecord r WHERE r.userId = :userId")
    int deleteAllByUserId(@Param("userId") String userId);
}
