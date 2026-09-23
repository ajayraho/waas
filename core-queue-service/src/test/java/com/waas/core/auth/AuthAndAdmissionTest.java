package com.waas.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waas.core.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthAndAdmissionTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode postJson(String url, String body) throws Exception {
        String res = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private String guestToken(String name) throws Exception {
        return postJson("/api/auth/guest", "{\"name\":\"" + name + "\"}").get("token").asText();
    }

    @Test
    void joiningNeedsAToken() throws Exception {
        UUID w = newWaitlist(1, 600);
        mvc.perform(post("/api/waitlists/" + w + "/entries")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/waitlists/" + w + "/entries").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/waitlists/" + w + "/entries").header("Authorization", "Bearer " + guestToken("G")))
                .andExpect(status().isCreated());
    }

    @Test
    void registerThenLogin() throws Exception {
        String email = "anshu+" + UUID.randomUUID() + "@test.local";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anshu\",\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-one\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheOwningTenantCanChangeAWaitlist() throws Exception {
        UUID w = newWaitlist(1, 600);  // owned by the demo tenant (key demo-api-key-001)
        jdbc.sql("INSERT INTO tenant (name, api_key) VALUES ('Other', 'other-key') ON CONFLICT DO NOTHING").update();
        String body = "{\"servingCapacity\":2}";

        mvc.perform(patch("/api/waitlists/" + w).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/waitlists/" + w).header("X-Api-Key", "other-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/waitlists/" + w).header("X-Api-Key", "demo-api-key-001")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servingCapacity").value(2));
    }

    @Test
    void tooManyWritesGet429WithRetryAfter() throws Exception {
        UUID w = newWaitlist(1, 600);
        String token = guestToken("Spammer");
        int limited = 0;
        for (int i = 0; i < 25; i++) {
            int s = mvc.perform(post("/api/waitlists/" + w + "/entries").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
            if (s == 429) {
                limited++;
            }
        }
        assertThat(limited).isEqualTo(5); // default limit 20 per 10 s
        mvc.perform(post("/api/waitlists/" + w + "/entries").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void anIdempotencyKeyReplaysTheFirstResponse() throws Exception {
        UUID w = newWaitlist(1, 600);
        String token = guestToken("Creator");
        UUID friend = newUser("Friend");
        String body = "{\"memberIds\":[\"" + friend + "\"]}";

        String first = mvc.perform(post("/api/waitlists/" + w + "/groups").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "abc-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Without the key this would be 409 ALREADY_QUEUED; with it, the first response comes back.
        String second = mvc.perform(post("/api/waitlists/" + w + "/groups").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "abc-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM waitlist_group WHERE waitlist_id = :w").param("w", w)
                .query(Long.class).single()).isEqualTo(1);
    }
}
