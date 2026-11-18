package com.jim.wms.common.lock;

import com.jim.wms.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 Redis {@code SET key token PX lease NX} 的分布式锁。
 *
 * <p>正常运行路径只有 Redis 一条：</p>
 * <ul>
 *     <li>锁值 token = {@code 实例标识:线程ID:UUID}，实例标识为 {@code spring.application.name:server.port}，
 *     不同实例/线程/同线程多次获取均不同；</li>
 *     <li>释放通过 Lua 脚本比对 token 后再 del，防止误删他人锁；返回 0（锁已过期或被他人持有）只记 WARN；</li>
 *     <li>获取为有界等待：在 {@code waitTimeout} 内按 {@code retryInterval} 重试，超时抛 429；</li>
 *     <li>Redis 不可用（连接失败/超时等）抛 503 快速失败，不静默降级、不静默放行；</li>
 *     <li>存在活动事务时在 {@code afterCompletion}（提交/回滚后）释放，无事务时在 finally 释放，
 *     并用 {@link AtomicBoolean} 保证只释放一次；释放失败只记 ERROR，不吞掉/覆盖原始业务异常。</li>
 * </ul>
 *
 * <p>进程内兜底锁仅在 {@code wms.lock.allow-local-fallback=true} 显式打开时作为兜底分支存在，
 * 该开关默认 false，正常运行路径完全不经过本地锁。</p>
 */
@Slf4j
public class RedisLockUtil {

    /**
     * 释放锁 Lua：仅当 key 的值等于本持有者 token 时才 del。
     * 返回 1 表示删除成功；返回 0 表示锁已不属于自己（已过期或被他人持有），不得删除。
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT;

    static {
        RELEASE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_SCRIPT.setScriptText(
                "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end");
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final LockProperties properties;
    private final RedisProperties redisProperties;
    /** 实例标识：{@code spring.application.name:server.port}，参与 token 生成。 */
    private final String instanceId;

    /**
     * 进程内分段锁表。仅 {@code allow-local-fallback=true} 的兜底分支使用；
     * 默认路径不读取、不写入本结构。
     */
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public RedisLockUtil(StringRedisTemplate stringRedisTemplate,
                         LockProperties properties,
                         RedisProperties redisProperties,
                         String instanceId) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.redisProperties = redisProperties;
        this.instanceId = instanceId;
    }

    /**
     * 启动自检：PING 一次 Redis。成功记 INFO；失败记 ERROR，但不阻断启动
     * （report/integration 等不使用锁的服务仍要能起来）。
     */
    @PostConstruct
    void checkRedisOnStartup() {
        try {
            String pong = stringRedisTemplate.getConnectionFactory().getConnection().ping();
            log.info("Redis 锁通道就绪（{}:{}:{}），PING={}",
                    redisProperties.getHost(), redisProperties.getPort(),
                    redisProperties.getDatabase(), pong);
        } catch (Exception e) {
            log.error("Redis 不可用，加锁操作将快速失败(503)，原因={}", e.getMessage());
            if (properties.isAllowLocalFallback()) {
                log.warn("wms.lock.allow-local-fallback=true：进程内兜底锁仅限单机开发，生产环境禁用");
            }
        }
    }

    /**
     * 获取锁，等待超时消息沿用库存 429 文案。
     *
     * @param key 锁 key
     * @return 锁句柄，调用方在 finally（或事务同步回调）中调用 {@link LockHandle#release()}
     */
    public LockHandle acquire(String key) {
        return acquire(key, "该库存维度正在被其他任务变更，请稍后重试: " + key);
    }

    /**
     * 获取锁。
     *
     * @param key          锁 key
     * @param busyMessage 等待超时（429）时的业务文案
     * @return 锁句柄
     */
    public LockHandle acquire(String key, String busyMessage) {
        String token = newToken();
        try {
            boolean ok = tryAcquireRedis(key, token);
            if (ok) {
                LockHandle handle = LockHandle.redis(this, key, token);
                registerTxRelease(handle);
                return handle;
            }
            throw new BusinessException(429, busyMessage);
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "Redis 锁服务不可用，已拒绝本次库存变更: 等待锁过程被中断");
        } catch (Exception e) {
            if (properties.isAllowLocalFallback()) {
                return acquireLocalFallback(key, busyMessage);
            }
            throw new BusinessException(503,
                    "Redis 锁服务不可用，已拒绝本次库存变更: " + e.getMessage());
        }
    }

    /**
     * 有界等待获取 Redis 锁：{@code SET key token PX lease NX}，失败后按 retryInterval 重试，
     * 直到 waitTimeout 截止。
     *
     * @return true 抢到；false 等待超时仍未抢到
     */
    private boolean tryAcquireRedis(String key, String token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + properties.getWaitTimeout().toMillis();
        while (true) {
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, token, properties.getLeaseTime());
            if (Boolean.TRUE.equals(ok)) {
                log.info("Redis 加锁成功 key={} token={}", key, token);
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(properties.getRetryInterval().toMillis());
        }
    }

    /**
     * 兜底分支（仅 allow-local-fallback=true）：回退进程内 ReentrantLock，每次兜底打 WARN。
     */
    private LockHandle acquireLocalFallback(String key, String busyMessage) {
        log.warn("Redis 不可用，按 wms.lock.allow-local-fallback=true 回退进程内锁 key={}（仅限单机开发，生产禁用）", key);
        ReentrantLock lock = localLocks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired = lock.tryLock();
        if (!acquired) {
            throw new BusinessException(429, busyMessage);
        }
        LockHandle handle = LockHandle.local(this, key, lock);
        registerTxRelease(handle);
        return handle;
    }

    /**
     * 存在活动事务时注册 afterCompletion 回调，在事务提交/回滚后释放锁；
     * 无事务时由调用方在 finally 释放。
     */
    private void registerTxRelease(LockHandle handle) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    release(handle);
                }
            });
        }
    }

    /**
     * 释放锁。幂等：AtomicBoolean 保证只释放一次；释放失败只记 ERROR，不抛出、不覆盖原始业务异常。
     */
    void release(LockHandle handle) {
        if (!handle.released.compareAndSet(false, true)) {
            return;
        }
        if (!handle.redis) {
            if (handle.localLock != null && handle.localLock.isHeldByCurrentThread()) {
                handle.localLock.unlock();
            }
            return;
        }
        try {
            Long released = stringRedisTemplate.execute(
                    RELEASE_SCRIPT, Collections.singletonList(handle.key), handle.token);
            if (released == null || released == 0L) {
                // 锁已过期或被他人持有：不再删除，只记 WARN
                log.warn("释放 Redis 锁时锁已不属于本持有者 key={} token={}（已过期或被他人持有），不再删除",
                        handle.key, handle.token);
            } else {
                log.info("Redis 释放锁成功 key={} token={}", handle.key, handle.token);
            }
        } catch (Exception e) {
            log.error("释放 Redis 锁失败 key={} token={}，原因={}", handle.key, handle.token, e.getMessage());
        }
    }

    /** 锁值：实例标识:线程ID:UUID，保证不同实例/线程/同线程多次获取均不同。 */
    private String newToken() {
        return instanceId + ":" + Thread.currentThread().getId() + ":" + UUID.randomUUID();
    }

    /**
     * 锁句柄：携带 key、token、释放通道标识；release 幂等。
     */
    public static final class LockHandle {

        private final RedisLockUtil owner;
        private final String key;
        private final String token;
        /** true=Redis 锁；false=进程内兜底锁。 */
        private final boolean redis;
        private final ReentrantLock localLock;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private LockHandle(RedisLockUtil owner, String key, String token,
                           boolean redis, ReentrantLock localLock) {
            this.owner = owner;
            this.key = key;
            this.token = token;
            this.redis = redis;
            this.localLock = localLock;
        }

        static LockHandle redis(RedisLockUtil owner, String key, String token) {
            return new LockHandle(owner, key, token, true, null);
        }

        static LockHandle local(RedisLockUtil owner, String key, ReentrantLock localLock) {
            return new LockHandle(owner, key, null, false, localLock);
        }

        /** 释放锁，幂等。 */
        public void release() {
            owner.release(this);
        }
    }
}
