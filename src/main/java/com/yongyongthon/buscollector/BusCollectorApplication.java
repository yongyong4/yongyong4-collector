package com.yongyongthon.buscollector;

import com.yongyongthon.buscollector.config.CollectorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CollectorProperties.class)
public class BusCollectorApplication {

    public static void main(String[] args) {
        // DB DATETIME 은 타임존이 없으므로 JVM 전체를 KST 로 고정한다
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(BusCollectorApplication.class, args);
    }
}
