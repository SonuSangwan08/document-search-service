package com.docsearch.controller;

import com.docsearch.TestcontainersConfiguration;
import com.docsearch.dto.DocumentResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the async indexing pipeline: log in, POST a document,
 * wait for the outbox relay + Kafka consumer to make it searchable, then
 * confirm it shows up in GET /search with highlighting, and that DELETE
 * removes it again. Requires Docker - run with {@code ./mvnw test}.
 * <p>
 * Auth is via POST /auth/login (see AuthController/JwtAuthFilter) rather
 * than the trusted X-Tenant-Id/X-User-Role headers this replaced - every
 * request below carries {@code Authorization: Bearer <token>} obtained from
 * one of the demo accounts seeded in V4__add_users.sql.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers
class DocumentApiIntegrationTest {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.18.3"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void esProperties(DynamicPropertyRegistry registry) {
        registry.add("app.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String AUTH_HEADER = "Authorization";

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new com.docsearch.dto.LoginRequest(username, password));
        String response = mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @Timeout(30)
    void createThenSearchThenDelete() throws Exception {
        String adminToken = loginAndGetToken("admin@acme", "password123");
        String userToken = loginAndGetToken("user@acme", "password123");

        String createBody = """
                {"title":"Quarterly Revenue Report","content":"Enterprise revenue grew 42 percent driven by search platform adoption.","tags":["finance","q3"]}
                """;

        String responseBody = mockMvc.perform(post("/documents")
                        .header(AUTH_HEADER, bearer(adminToken))
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        UUID documentId = objectMapper.readValue(responseBody, DocumentResponse.class).id();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> mockMvc.perform(get("/search")
                                .header(AUTH_HEADER, bearer(userToken))
                                .param("q", "revenue"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalHits").value(1))
                        .andExpect(jsonPath("$.hits[0].title", containsString("Revenue"))));

        mockMvc.perform(get("/documents/" + documentId)
                        .header(AUTH_HEADER, bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INDEXED"));

        mockMvc.perform(delete("/documents/" + documentId)
                        .header(AUTH_HEADER, bearer(adminToken)))
                .andExpect(status().isNoContent());

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> mockMvc.perform(get("/search")
                                .header(AUTH_HEADER, bearer(userToken))
                                .param("q", "revenue"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalHits").value(0)));
    }

    @Test
    void missingOrInvalidTokenIsRejected() throws Exception {
        mockMvc.perform(get("/documents/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/documents/00000000-0000-0000-0000-000000000000")
                        .header(AUTH_HEADER, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new com.docsearch.dto.LoginRequest("admin@acme", "wrong-password"));
        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRoleCannotCreateOrDeleteDocuments() throws Exception {
        String userToken = loginAndGetToken("user@acme", "password123");

        String createBody = """
                {"title":"Read Only Probe","content":"USER role must not be able to write."}
                """;

        mockMvc.perform(post("/documents")
                        .header(AUTH_HEADER, bearer(userToken))
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/documents/00000000-0000-0000-0000-000000000000")
                        .header(AUTH_HEADER, bearer(userToken)))
                .andExpect(status().isForbidden());
    }
}
