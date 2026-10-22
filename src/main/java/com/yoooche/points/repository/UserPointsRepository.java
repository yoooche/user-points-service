package com.yoooche.points.repository;

import com.yoooche.points.model.UserPoints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPointsRepository extends JpaRepository<UserPoints, String> {


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_points (user_id, total) VALUES (:userId, :amount) AS new
            ON DUPLICATE KEY UPDATE total = user_points.total + new.total
            """, nativeQuery = true)
    void addToTotal(@Param("userId") String userId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserPoints p WHERE p.userId = :userId")
    int deleteAllByUserId(@Param("userId") String userId);

    List<UserPoints> findTop10ByOrderByTotalDescUserIdAsc();
}
