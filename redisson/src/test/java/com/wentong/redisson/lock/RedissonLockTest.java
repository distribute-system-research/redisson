package com.wentong.redisson.lock;

import com.wentong.redisson.BaseTest;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;

public class RedissonLockTest extends BaseTest {

    @Test
    void testLockBase() throws Exception {
        RLock rLock = redisson.getLock("unfair_lock");
        rLock.lock();
        System.out.println(rLock);
        new Thread(() -> {
            rLock.lock();
            rLock.unlock();
        }).start();
        rLock.unlock();
        TimeUnit.SECONDS.sleep(1);
    }

    @Override
    public boolean flushAfterExec() {
        return false;
    }
}
