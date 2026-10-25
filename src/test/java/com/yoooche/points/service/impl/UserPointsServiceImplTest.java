package com.yoooche.points.service.impl;

import com.yoooche.points.cache.UserPointsCache;
import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.exception.BaseException;
import com.yoooche.points.model.UserPointsRecord;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPointsServiceImplTest {

    @Mock
    private UserPointsRecordRepository recordRepository;

    @Mock
    private UserPointsRepository pointsRepository;

    @Mock
    private UserPointsCache userPointsCache;

    @InjectMocks
    private UserPointsServiceImpl service;

    @Test
    void getTotalUserPointsThrowsWhenUserHasNoPoints() {
        when(userPointsCache.getTotal("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTotalUserPoints("ghost"))
                .isInstanceOf(BaseException.class)
                .extracting("statusCode").isEqualTo(StatusCode.USER_NOT_FOUND);
    }

    @Test
    void updateReasonChangesOnlyTheReason() {
        UserPointsRecord record = new UserPointsRecord();
        record.setRecordId(7L);
        record.setAmount(100);
        record.setReason("old");
        when(recordRepository.findById(7L)).thenReturn(Optional.of(record));

        PointRecordResponse response = service.updateReason(7L, "new");

        assertThat(response.reason()).isEqualTo("new");
        assertThat(response.amount()).isEqualTo(100L);
    }

    @Test
    void updateReasonThrowsWhenRecordMissing() {
        when(recordRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateReason(99L, "x"))
                .isInstanceOf(BaseException.class)
                .extracting("statusCode").isEqualTo(StatusCode.RECORD_NOT_FOUND);
    }

    @Test
    void deleteThrowsWhenUserHasNothingToDelete() {
        when(recordRepository.deleteAllByUserId("ghost")).thenReturn(0);
        when(pointsRepository.deleteAllByUserId("ghost")).thenReturn(0);

        assertThatThrownBy(() -> service.deleteUserPoints("ghost"))
                .isInstanceOf(BaseException.class)
                .extracting("statusCode").isEqualTo(StatusCode.USER_NOT_FOUND);
    }
}
