package com.wentong.redisson;

import org.junit.jupiter.api.BeforeAll;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

public abstract class BaseTest {

    protected static RedissonClient redisson;

    @BeforeAll
    public static void beforeAll() {
        redisson = createInstance();
    }

    public static RedissonClient createInstance() {
        Config config = createConfig();
        return Redisson.create(config);
    }

    public static Config createConfig() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379");
        return config;
    }

}
