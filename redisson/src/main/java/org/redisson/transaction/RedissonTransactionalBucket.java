/**
 * Copyright (c) 2013-2021 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.transaction;

import io.netty.buffer.ByteBuf;
import org.redisson.RedissonBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.client.codec.Codec;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.CompletableFutureWrapper;
import org.redisson.transaction.operation.DeleteOperation;
import org.redisson.transaction.operation.TouchOperation;
import org.redisson.transaction.operation.TransactionalOperation;
import org.redisson.transaction.operation.UnlinkOperation;
import org.redisson.transaction.operation.bucket.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 
 * @author Nikita Koksharov
 *
 * @param <V> value type
 */
public class RedissonTransactionalBucket<V> extends RedissonBucket<V> {

    static final Object NULL = new Object();
    
    private long timeout;
    private final AtomicBoolean executed;
    private final List<TransactionalOperation> operations;
    private Object state;
    private final String transactionId;
    
    public RedissonTransactionalBucket(CommandAsyncExecutor commandExecutor, long timeout, String name, List<TransactionalOperation> operations, AtomicBoolean executed, String transactionId) {
        super(commandExecutor, name);
        this.operations = operations;
        this.executed = executed;
        this.transactionId = transactionId;
        this.timeout = timeout;
    }

    public RedissonTransactionalBucket(Codec codec, CommandAsyncExecutor commandExecutor, long timeout, String name, List<TransactionalOperation> operations, AtomicBoolean executed, String transactionId) {
        super(codec, commandExecutor, name);
        this.operations = operations;
        this.executed = executed;
        this.transactionId = transactionId;
        this.timeout = timeout;
    }
    
    @Override
    public RFuture<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit, String param, String... keys) {
        throw new UnsupportedOperationException("expire method is not supported in transaction");
    }
    
    @Override
    protected RFuture<Boolean> expireAtAsync(long timestamp, String param, String... keys) {
        throw new UnsupportedOperationException("expire method is not supported in transaction");
    }

    @Override
    public RFuture<Boolean> clearExpireAsync() {
        throw new UnsupportedOperationException("clearExpire method is not supported in transaction");
    }
    
    @Override
    public RFuture<Boolean> moveAsync(int database) {
        throw new UnsupportedOperationException("moveAsync method is not supported in transaction");
    }
    
    @Override
    public RFuture<Void> migrateAsync(String host, int port, int database, long timeout) {
        throw new UnsupportedOperationException("migrateAsync method is not supported in transaction");
    }
    
    @Override
    public RFuture<Long> sizeAsync() {
        checkState();
        if (state != null) {
            if (state == NULL) {
                return new CompletableFutureWrapper<>(0L);
            } else {
                ByteBuf buf = encode(state);
                long size = buf.readableBytes();
                buf.release();
                return new CompletableFutureWrapper<>(size);
            }
        }

        return super.sizeAsync();
    }
    
    @Override
    public RFuture<Boolean> isExistsAsync() {
        checkState();
        if (state != null) {
            if (state == NULL) {
                return new CompletableFutureWrapper<>(null);
            } else {
                return new CompletableFutureWrapper<>(true);
            }
        }
        
        return super.isExistsAsync();
    }
    
    @Override
    public RFuture<Boolean> touchAsync() {
        checkState();
        long currentThreadId = Thread.currentThread().getId();
        return executeLocked(() -> {
            if (state != null) {
                operations.add(new TouchOperation(getRawName(), getLockName(), currentThreadId));
                return CompletableFuture.completedFuture(state != NULL);
            }

            return isExistsAsync().thenApply(res -> {
                operations.add(new TouchOperation(getRawName(), getLockName(), currentThreadId));
                return res;
            });
        });
    }

    @Override
    public RFuture<Boolean> unlinkAsync() {
        checkState();
        long currentThreadId = Thread.currentThread().getId();
        return executeLocked(() -> {
            if (state != null) {
                operations.add(new UnlinkOperation(getRawName(), getLockName(), currentThreadId));
                if (state == NULL) {
                    return CompletableFuture.completedFuture(false);
                } else {
                    state = NULL;
                    return CompletableFuture.completedFuture(true);
                }
            }

            return isExistsAsync().thenApply(res -> {
                operations.add(new UnlinkOperation(getRawName(), getLockName(), currentThreadId));
                state = NULL;
                return res;
            });
        });
    }
    
    @Override
    public RFuture<Boolean> deleteAsync() {
        checkState();
        long threadId = Thread.currentThread().getId();
        return executeLocked(() -> {
            if (state != null) {
                operations.add(new DeleteOperation(getRawName(), getLockName(), transactionId, threadId));
                if (state == NULL) {
                    return CompletableFuture.completedFuture(false);
                } else {
                    state = NULL;
                    return CompletableFuture.completedFuture(true);
                }
            }

            return isExistsAsync().thenApply(res -> {
                operations.add(new DeleteOperation(getRawName(), getLockName(), transactionId, threadId));
                state = NULL;
                return res;
            });
        });
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public RFuture<V> getAsync() {
        checkState();
        if (state != null) {
            if (state == NULL) {
                return new CompletableFutureWrapper<>((V) null);
            } else {
                return new CompletableFutureWrapper<>((V) state);
            }
        }
        
        return super.getAsync();
    }
    
    @Override
    public RFuture<Boolean> compareAndSetAsync(V expect, V update) {
        checkState();
        long currentThreadId = Thread.currentThread().getId();
        return executeLocked(() -> {
            if (state != null) {
                operations.add(new BucketCompareAndSetOperation<V>(getRawName(), getLockName(), getCodec(), expect, update, transactionId, currentThreadId));
                if ((state == NULL && expect == null)
                        || isEquals(state, expect)) {
                    if (update == null) {
                        state = NULL;
                    } else {
                        state = update;
                    }
                    return CompletableFuture.completedFuture(true);
                }
                return CompletableFuture.completedFuture(false);
            }

            return getAsync().thenApply(res -> {
                operations.add(new BucketCompareAndSetOperation<V>(getRawName(), getLockName(), getCodec(), expect, update, transactionId, currentThreadId));
                if ((res == null && expect == null)
                        || isEquals(res, expect)) {
                    if (update == null) {
                        state = NULL;
                    } else {
                        state = update;
                    }
                    return true;
                }
                return false;
            });
        });
    }
    
    @Override
    public RFuture<V> getAndSetAsync(V value, long timeToLive, TimeUnit timeUnit) {
        return getAndSet(value, new BucketGetAndSetOperation<V>(getRawName(), getLockName(), getCodec(), value, timeToLive, timeUnit, transactionId));
    }
    
    @Override
    public RFuture<V> getAndSetAsync(V value) {
        return getAndSet(value, new BucketGetAndSetOperation<V>(getRawName(), getLockName(), getCodec(), value, transactionId));
    }
    
    @SuppressWarnings("unchecked")
    private RFuture<V> getAndSet(V newValue, TransactionalOperation operation) {
        checkState();
        return executeLocked(() -> {
            if (state != null) {
                Object prevValue;
                if (state == NULL) {
                    prevValue = null;
                } else {
                    prevValue = state;
                }
                operations.add(operation);
                if (newValue == null) {
                    state = NULL;
                } else {
                    state = newValue;
                }
                return CompletableFuture.completedFuture((V) prevValue);
            }

            return getAsync().thenApply(res -> {
                if (newValue == null) {
                    state = NULL;
                } else {
                    state = newValue;
                }
                operations.add(operation);
                return res;
            });
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public RFuture<V> getAndDeleteAsync() {
        checkState();
        long currentThreadId = Thread.currentThread().getId();
        return executeLocked(() -> {
            if (state != null) {
                Object prevValue;
                if (state == NULL) {
                    prevValue = null;
                } else {
                    prevValue = state;
                }
                operations.add(new BucketGetAndDeleteOperation<V>(getRawName(), getLockName(), getCodec(), transactionId, currentThreadId));
                state = NULL;
                return CompletableFuture.completedFuture((V) prevValue);
            }

            return getAsync().thenApply(res -> {
                state = NULL;
                operations.add(new BucketGetAndDeleteOperation<V>(getRawName(), getLockName(), getCodec(), transactionId, currentThreadId));
                return res;
            });
        });
    }
    
    @Override
    public RFuture<Void> setAsync(V newValue) {
        long currentThreadId = Thread.currentThread().getId();
        return setAsync(newValue, new BucketSetOperation<V>(getRawName(), getLockName(), getCodec(), newValue, transactionId, currentThreadId));
    }

    private RFuture<Void> setAsync(V newValue, TransactionalOperation operation) {
        checkState();
        return executeLocked(() -> {
            operations.add(operation);
            if (newValue == null) {
                state = NULL;
            } else {
                state = newValue;
            }
            return CompletableFuture.completedFuture(null);
        });
    }
    
    @Override
    public RFuture<Void> setAsync(V value, long timeToLive, TimeUnit timeUnit) {
        long currentThreadId = Thread.currentThread().getId();
        return setAsync(value, new BucketSetOperation<V>(getRawName(), getLockName(), getCodec(), value, timeToLive, timeUnit, transactionId, currentThreadId));
    }
    
    @Override
    public RFuture<Boolean> trySetAsync(V newValue) {
        long currentThreadId = Thread.currentThread().getId();
        return trySet(newValue, new BucketTrySetOperation<V>(getRawName(), getLockName(), getCodec(), newValue, transactionId, currentThreadId));
    }
    
    @Override
    public RFuture<Boolean> trySetAsync(V value, long timeToLive, TimeUnit timeUnit) {
        long currentThreadId = Thread.currentThread().getId();
        return trySet(value, new BucketTrySetOperation<V>(getRawName(), getLockName(), getCodec(), value, timeToLive, timeUnit, transactionId, currentThreadId));
    }

    private RFuture<Boolean> trySet(V newValue, TransactionalOperation operation) {
        checkState();
        return executeLocked(() -> {
            if (state != null) {
                operations.add(operation);
                if (state == NULL) {
                    if (newValue == null) {
                        state = NULL;
                    } else {
                        state = newValue;
                    }
                    return CompletableFuture.completedFuture(true);
                } else {
                    return CompletableFuture.completedFuture(false);
                }
            }

            return getAsync().thenApply(res -> {
                operations.add(operation);
                if (res == null) {
                    if (newValue == null) {
                        state = NULL;
                    } else {
                        state = newValue;
                    }
                    return true;
                }
                return false;
            });
        });
    }

    private boolean isEquals(Object value, Object oldValue) {
        ByteBuf valueBuf = encode(value);
        ByteBuf oldValueBuf = encode(oldValue);
        
        try {
            return valueBuf.equals(oldValueBuf);
        } finally {
            valueBuf.readableBytes();
            oldValueBuf.readableBytes();
        }
    }
    
    protected <R> RFuture<R> executeLocked(Supplier<CompletionStage<R>> runnable) {
        RLock lock = getLock();
        CompletionStage<R> f = lock.lockAsync(timeout, TimeUnit.MILLISECONDS).thenCompose(res -> runnable.get());
        return new CompletableFutureWrapper<>(f);
    }

    private RLock getLock() {
        return new RedissonTransactionalLock(commandExecutor, getLockName(), transactionId);
    }

    private String getLockName() {
        return getRawName() + ":transaction_lock";
    }

    protected void checkState() {
        if (executed.get()) {
            throw new IllegalStateException("Unable to execute operation. Transaction is in finished state!");
        }
    }
    
}
