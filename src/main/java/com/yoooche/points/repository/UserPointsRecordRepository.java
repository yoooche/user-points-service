package com.yoooche.points.repository;

import com.yoooche.points.model.UserPointsRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPointsRecordRepository extends JpaRepository<UserPointsRecord, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserPointsRecord r WHERE r.userId = :userId")
    int deleteAllByUserId(@Param("userId") String userId);
}
