package com.waas.core.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waas.core.AbstractIntegrationTest;
import com.waas.core.queue.QueueService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The waitlist directory: tenant isolation, search, paging, live counts, and creating waitlists. */
@AutoConfigureMockMvc
class WaitlistDirectoryTest extends AbstractIntegrationTest {

    private static final String DEMO_KEY = "demo-api-key-001";   // Demo Corp (seeded)
    private static final String NIMBUS_KEY = "demo-api-key-002"; // Nimbus Games (seeded)

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired QueueService queue;

    private JsonNode list(String key, String query) throws Exception {
        String body = mvc.perform(get("/api/waitlists").header("X-Api-Key", key).param("q", query).param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("name").asText()));
        return out;
    }

    private UUID create(String key, String name) throws Exception {
        String body = mvc.perform(post("/api/waitlists").header("X-Api-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"servingCapacity\":2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    @Test
    void listingNeedsAKnownApiKey() throws Exception {
        mvc.perform(get("/api/waitlists")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/waitlists").header("X-Api-Key", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void eachTenantSeesOnlyItsOwnWaitlists() throws Exception {
        JsonNode demoPage = list(DEMO_KEY, "");
        JsonNode nimbusPage = list(NIMBUS_KEY, "");
        assertThat(demoPage.get("tenant").asText()).isEqualTo("Demo Corp");
        assertThat(nimbusPage.get("tenant").asText()).isEqualTo("Nimbus Games");
        List<String> demo = names(demoPage);
        List<String> nimbus = names(nimbusPage);

        assertThat(demo).contains("AirMax Drop", "Beta Access").doesNotContain("Starfall Beta", "Dev Stream Q&A");
        assertThat(nimbus).contains("Starfall Beta", "Dev Stream Q&A").doesNotContain("AirMax Drop");
    }

    @Test
    void createdWaitlistBelongsToTheCallersTenant() throws Exception {
        String name = "Nimbus Launch " + UUID.randomUUID();
        create(NIMBUS_KEY, name);

        assertThat(names(list(NIMBUS_KEY, name))).containsExactly(name);
        assertThat(list(DEMO_KEY, name).get("total").asLong()).isZero();
    }

    @Test
    void searchIsCaseInsensitiveAndTreatsWildcardsLiterally() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        create(DEMO_KEY, "Night_Market " + tag);
        create(DEMO_KEY, "Nightly Build " + tag);

        assertThat(names(list(DEMO_KEY, "night_market " + tag))).containsExactly("Night_Market " + tag);
        assertThat(list(DEMO_KEY, "NIGHT").get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(list(DEMO_KEY, "%").get("total").asLong()).isZero(); // '%' is a character, not "match all"
    }

    @Test
    void pagesAreBoundedAndCountTheTotal() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            create(DEMO_KEY, "Paged " + tag + " " + i);
        }
        mvc.perform(get("/api/waitlists").header("X-Api-Key", DEMO_KEY).param("q", tag).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1)) // page 0 has 2, page 1 has the last one
                .andExpect(jsonPath("$.page").value(1));
        mvc.perform(get("/api/waitlists").header("X-Api-Key", DEMO_KEY).param("size", "5000"))
                .andExpect(jsonPath("$.size").value(WaitlistService.MAX_PAGE_SIZE));
    }

    @Test
    void cardsCarryLiveCountsFromRedis() throws Exception {
        String name = "Counted " + UUID.randomUUID();
        UUID w = create(DEMO_KEY, name); // 2 serving slots
        for (int i = 0; i < 5; i++) {
            queue.join(w, newUser("C" + i));
        }
        // the first two joins went straight into the free slots, three are waiting
        JsonNode card = list(DEMO_KEY, name).get("items").get(0);
        assertThat(card.get("waiting").asLong()).isEqualTo(3);
        assertThat(card.get("reserved").asLong()).isEqualTo(2);
    }

    @Test
    void createRejectsBadInput() throws Exception {
        mvc.perform(post("/api/waitlists").header("X-Api-Key", DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/waitlists").header("X-Api-Key", DEMO_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"groupPolicy\":\"SOMETIMES\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/waitlists").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }
}
