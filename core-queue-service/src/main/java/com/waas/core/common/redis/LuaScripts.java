package com.waas.core.common.redis;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Lua scripts live as {@code .lua} files under {@code resources/redis/} so they can be read,
 * reviewed and tested with {@code redis-cli --eval} on their own.
 *
 * <p>Spring sends each script with EVALSHA (by its SHA1) and only falls back to sending the
 * full source on NOSCRIPT, so the script body isn't re-uploaded on every call.
 */
@Configuration
public class LuaScripts {

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> load(String name) {
        return RedisScript.of(new ClassPathResource("redis/" + name + ".lua"), List.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> joinScript() {
        return load("join");
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> locateScript() {
        return load("locate");
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> snapshotScript() {
        return load("snapshot");
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> promoteScript() {
        return load("promote");
    }

    @Bean
    public RedisScript<Long> confirmScript() {
        return RedisScript.of(new ClassPathResource("redis/confirm.lua"), Long.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> expireDueScript() {
        return load("expire_due");
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> bumpScript() {
        return load("bump");
    }

    @Bean
    public RedisScript<String> rebuildSwapScript() {
        return RedisScript.of(new ClassPathResource("redis/rebuild_swap.lua"), String.class);
    }
}
