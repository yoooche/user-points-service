package com.yoooche.points.repository;

import com.yoooche.points.model.OutboxMessage;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    List<OutboxMessage> findByIdGreaterThanOrderByIdAsc(long id, Limit limit);
}
