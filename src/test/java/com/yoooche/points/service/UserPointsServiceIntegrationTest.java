package com.yoooche.points.service;

import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.exception.BaseException;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserPointsServiceIntegrationTest {

    @Autowired
    private UserPointsService service;

    @Autowired
    private UserPointsRecordRepository recordRepository;

    @Autowired
    private UserPointsRepository pointsRepository;

    @AfterEach
    void cleanUp() {
        recordRepository.deleteAll();
        pointsRepository.deleteAll();
    }

    /** Proves the atomic upsert: a read-modify-write in Java would lose some of these updates. */
    @Test
    void concurrentAddsForSameUserAreNotLost() throws Exception {
        int requests = 50;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            var futures = IntStream.range(0, requests)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return service.addPoints(new AddPointsRequest("hot_user", 10L, "concurrent"));
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(service.getTotalUserPoints("hot_user").total()).isEqualTo(requests * 10L);
        assertThat(recordRepository.count()).isEqualTo(requests);
    }

    @Test
    void deleteRemovesRecordsAndTotal() {
        service.addPoints(new AddPointsRequest("user_1", 10L, "a"));
        service.addPoints(new AddPointsRequest("user_1", 20L, "b"));

        service.deleteUserPoints("user_1");

        assertThat(recordRepository.count()).isZero();
        assertThat(pointsRepository.findById("user_1")).isEmpty();
    }

    @Test
    void createdAtIsTheSameInPostAndPutResponses() {
        PointRecordResponse created = service.addPoints(new AddPointsRequest("user_1", 10L, "a"));

        PointRecordResponse updated = service.updateReason(created.id(), "b");

        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(created.createdAt().getNano() % 1_000_000).isZero();
    }

    @Test
    void totalOverflowIsRejectedAndRolledBack() {
        // The service itself has no per-request cap (that is enforced at the API), so it can seed the maximum.
        service.addPoints(new AddPointsRequest("rich_user", Long.MAX_VALUE, "seed"));

        assertThatThrownBy(() -> service.addPoints(new AddPointsRequest("rich_user", 1L, "overflow")))
                .isInstanceOf(BaseException.class)
                .extracting("statusCode").isEqualTo(StatusCode.TOTAL_LIMIT_EXCEEDED);
        assertThat(recordRepository.count()).isEqualTo(1);
        assertThat(service.getTotalUserPoints("rich_user").total()).isEqualTo(Long.MAX_VALUE);
    }
}
