package io.github.susongyan.bobapop.demo;

import io.github.susongyan.bobapop.core.RedisLockClient;
import io.github.susongyan.bobapop.spring.RedisTemplateLockBackend;
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
