package com.yoooche.points.repository;

import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.model.UserPoints;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserPointsRepositoryTest {

    @Autowired
    private UserPointsRepository repository;

    @Test
    void addToTotalInsertsThenAccumulates() {
        repository.addToTotal("user_1", 100);
        repository.addToTotal("user_1", 50);

        assertThat(repository.findById("user_1")).get()
                .extracting(UserPoints::getTotal)
                .isEqualTo(150L);
    }

    @Test
    void leaderboardOrdersByTotalThenUserIdAndKeepsTopTen() {
        for (int i = 1; i <= 12; i++) {
            repository.addToTotal("user_%02d".formatted(i), i * 10L);
        }
        repository.addToTotal("user_00", 120);

        assertThat(repository.findTop10ByOrderByTotalDescUserIdAsc())
                .extracting(UserPoints::getUserId)
                .containsExactly("user_00", "user_12", "user_11", "user_10", "user_09",
                        "user_08", "user_07", "user_06", "user_05", "user_04");
    }

    @Test
    void deleteAllByUserIdReportsWhetherARowWasRemoved() {
        repository.addToTotal("user_1", 10);

        assertThat(repository.deleteAllByUserId("user_1")).isEqualTo(1);
        assertThat(repository.deleteAllByUserId("user_1")).isZero();
    }
}
