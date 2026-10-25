package com.yoooche.points.repository;

import com.yoooche.points.model.OutboxDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxDeadLetterRepository extends JpaRepository<OutboxDeadLetter, Long> {
}
