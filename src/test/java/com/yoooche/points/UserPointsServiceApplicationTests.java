package com.yoooche.points;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserPointsServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
