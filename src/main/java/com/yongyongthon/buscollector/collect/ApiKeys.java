package com.yongyongthon.buscollector.collect;

import com.yongyongthon.buscollector.config.CollectorProperties.Job;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** job 마다 지정된 환경변수 이름(api-key-env)으로 인증키를 꺼낸다. */
@Component
public class ApiKeys {

    private final Environment env;

    public ApiKeys(Environment env) {
        this.env = env;
    }

    public String keyFor(Job job) {
        String v = env.getProperty(job.apiKeyEnv());
        return v == null || v.isBlank() ? null : v.trim();
    }
}
