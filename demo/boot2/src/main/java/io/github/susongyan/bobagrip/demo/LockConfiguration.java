package io.github.susongyan.bobagrip.demo;

import io.github.susongyan.bobagrip.core.RedisLockClient;
import io.github.susongyan.bobagrip.spring.RedisTemplateLockBackend;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class LockConfiguration {
    @Bean
    public RedisLockClient redisLockClient(StringRedisTemplate template) {
        return new RedisLockClient(new RedisTemplateLockBackend(template));
    }
}
