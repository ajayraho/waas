package com.waas.gateway.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waas.gateway.AbstractGatewayTest;
import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

class FanoutFlowTest extends AbstractGatewayTest {

    @LocalServerPort int port;
    @Autowired StringRedisTemplate redis;
    @Autowired FanoutService fanout;
    @Autowired SubscriptionRegistry registry;
    @Autowired ObjectMapper mapper;

    WebSocketStompClient client;
    StompSession session;

    @BeforeEach
    void connect() throws Exception {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void disconnect() {
        session.disconnect();
        client.stop();
    }

    private BlockingQueue<String> subscribe(String destination) {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((String) payload);
            }
        });
        return inbox;
    }

    private JsonNode next(BlockingQueue<String> inbox) throws Exception {
        String body = inbox.poll(5, TimeUnit.SECONDS);
        assertThat(body).as("expected a message").isNotNull();
        return mapper.readTree(body);
    }

    private void publish(String type, UUID waitlistId, UUID entryId) {
        redis.convertAndSend("waitlist:{" + waitlistId + "}:events",
                "{\"type\":\"" + type + "\",\"waitlistId\":\"" + waitlistId + "\",\"entryId\":\"" + entryId + "\"}");
    }

    private void awaitSubscriptions(int n) throws InterruptedException {
        for (int i = 0; i < 50 && registry.subscriptionCount() < n; i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void anEntryWatcherGetsItsStateOnSubscribeThenUpdatesAsOthersMove() throws Exception {
        UUID w = UUID.randomUUID();
        UUID e1 = UUID.randomUUID(), e2 = UUID.randomUUID(), e3 = UUID.randomUUID();
        String queue = PositionReader.queueKey(w);
        redis.opsForZSet().add(queue, e1.toString(), 1);
        redis.opsForZSet().add(queue, e2.toString(), 2);
        redis.opsForZSet().add(queue, e3.toString(), 3);

        BlockingQueue<String> updates = subscribe(Destinations.entry(w, e3));
        BlockingQueue<String> initial = subscribe("/app/waitlists/" + w + "/entries/" + e3);
        awaitSubscriptions(1);

        JsonNode first = next(initial);   // §21.8: current state in one round trip
        assertThat(first.get("state").asText()).isEqualTo("WAITING");
        assertThat(first.get("position").asLong()).isEqualTo(3);

        // Someone ahead leaves. e3 receives no event of its own, but the dirty-marked tick recomputes.
        redis.opsForZSet().remove(queue, e1.toString());
        publish("CANCELLED", w, e1);
        JsonNode moved = next(updates);
        assertThat(moved.get("position").asLong()).isEqualTo(2);
        assertThat(moved.get("queueSize").asLong()).isEqualTo(2);

        // e3 is promoted: a personal event, pushed immediately.
        redis.opsForZSet().remove(queue, e3.toString());
        redis.opsForZSet().add(PositionReader.reservedKey(w), e3.toString(), System.currentTimeMillis() + 60_000);
        publish("RESERVED", w, e3);
        JsonNode reserved = next(updates);
        assertThat(reserved.get("state").asText()).isEqualTo("RESERVED");
        assertThat(reserved.get("expiresAt").isNull()).isFalse();
    }

    @Test
    void unchangedPositionsAreNotResent() throws Exception {
        UUID w = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        redis.opsForZSet().add(PositionReader.queueKey(w), e1.toString(), 1);
        BlockingQueue<String> updates = subscribe(Destinations.entry(w, e1));
        awaitSubscriptions(1);

        publish("JOINED", w, UUID.randomUUID()); // first refresh: sends e1's state once
        next(updates);
        publish("JOINED", w, UUID.randomUUID()); // nothing about e1 changed
        assertThat(updates.poll(700, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void dashboardViewersGetCoreSnapshotsRelayed() throws Exception {
        UUID w = UUID.randomUUID();
        CORE_RESPONSES.put("/api/waitlists/" + w + "/queue", "{\"waitlistId\":\"" + w + "\",\"queueSize\":42}");

        BlockingQueue<String> initial = subscribe("/app/waitlists/" + w + "/queue");
        BlockingQueue<String> updates = subscribe(Destinations.queue(w));
        awaitSubscriptions(1);
        assertThat(next(initial).get("queueSize").asLong()).isEqualTo(42);

        publish("JOINED", w, UUID.randomUUID());
        assertThat(next(updates).get("queueSize").asLong()).isEqualTo(42);
    }

    @Test
    void eventsForUnwatchedWaitlistsCostNoPushes() throws Exception {
        long before = fanout.pushes.get();
        long fetches = fanout.snapshotFetches.get();
        for (int i = 0; i < 20; i++) {
            publish("JOINED", UUID.randomUUID(), UUID.randomUUID());
        }
        Thread.sleep(400);
        assertThat(fanout.eventsReceived.get()).isGreaterThanOrEqualTo(20);
        assertThat(fanout.pushes.get()).isEqualTo(before);
        assertThat(fanout.snapshotFetches.get()).isEqualTo(fetches);
    }

    @Test
    void destinationsParseOnlyWhatWeServe() {
        UUID w = UUID.randomUUID(), e = UUID.randomUUID();
        assertThat(Destinations.parse(Destinations.entry(w, e))).hasValue(new Destinations.Target(w, e));
        assertThat(Destinations.parse(Destinations.queue(w))).hasValue(new Destinations.Target(w, null));
        assertThat(Destinations.parse("/topic/something/else")).isEmpty();
    }
}
