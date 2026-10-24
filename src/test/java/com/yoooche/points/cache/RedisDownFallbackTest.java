package com.yoooche.points.cache;

import com.yoooche.points.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs in its own context so stopping Redis does not affect other test classes. */
@SpringBootTest(properties = "test.context=redis-down")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class RedisDownFallbackTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redis;

    @Test
    void apiKeepsWorkingFromMySqlWhenRedisIsDown() throws Exception {
        redis.stop();

        mockMvc.perform(post("/points").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user_1","amount":100,"reason":"a"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/points/user_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100));
        mockMvc.perform(get("/points/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("user_1"));
        mockMvc.perform(delete("/points/user_1"))
                .andExpect(status().isNoContent());
    }
}
