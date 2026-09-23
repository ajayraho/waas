package com.waas.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

class GatewayBootTest extends AbstractGatewayTest {

    @Autowired ApplicationContext context;

    @Test
    void stompBrokerIsConfigured() {
        assertThat(context.getBeansOfType(WebSocketMessageBrokerConfigurer.class)).isNotEmpty();
    }
}
