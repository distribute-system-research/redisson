package com.wentong.redisson.lock;

import com.wentong.redisson.BaseTest;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;

public class RedissonLockTest extends BaseTest {

    @Test
    void testLockBase() throws Exception {
        RLock rLock = redisson.getLock("fair_lock");
        rLock.lock();
        System.out.println(rLock);
        rLock.unlock();
    }

}
