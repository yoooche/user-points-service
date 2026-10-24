package com.yoooche.points.cache;

import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.constant.CacheNames;
import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.exception.BaseException;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import com.yoooche.points.service.UserPointsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class UserPointsCacheIntegrationTest {

    @Autowired
    private UserPointsService service;

    @Autowired
    private UserPointsRecordRepository recordRepository;

    @Autowired
    private UserPointsRepository pointsRepository;

    @Autowired
    @Qualifier("redisCacheManager")
    private CacheManager cacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    void repeatedReadIsServedFromCache() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        service.getTotalUserPoints("user_1");

        pointsRepository.deleteAll();

        assertThat(service.getTotalUserPoints("user_1").total()).isEqualTo(100L);
    }

    @Test
    void missingUserIsNotCached() {
        assertThatThrownBy(() -> service.getTotalUserPoints("ghost")).isInstanceOf(BaseException.class);

        assertThat(totalCache().get("ghost")).isNull();
    }

    @Test
    void addingPointsEvictsCachedTotal() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        service.getTotalUserPoints("user_1");

        service.addPoints(new AddPointsRequest("user_1", 50L, "b"));

        assertThat(totalCache().get("user_1")).isNull();
        assertThat(service.getTotalUserPoints("user_1").total()).isEqualTo(150L);
    }

    @Test
    void deletingUserEvictsCachedTotal() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        service.getTotalUserPoints("user_1");

        service.deleteUserPoints("user_1");

        assertThatThrownBy(() -> service.getTotalUserPoints("user_1"))
                .isInstanceOf(BaseException.class)
                .extracting("statusCode").isEqualTo(StatusCode.USER_NOT_FOUND);
    }

    @Test
    void evictionWaitsForCommitAndIsSkippedOnRollback() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        service.getTotalUserPoints("user_1");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.addPoints(new AddPointsRequest("user_1", 50L, "b"));
            assertThat(totalCache().get("user_1")).isNotNull();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(totalCache().get("user_1", UserPointsResponse.class).total()).isEqualTo(100L);
        assertThat(pointsRepository.findById("user_1").orElseThrow().getTotal()).isEqualTo(100L);
    }

    private Cache totalCache() {
        return cacheManager.getCache(CacheNames.USER_POINTS_TOTAL);
    }
}
