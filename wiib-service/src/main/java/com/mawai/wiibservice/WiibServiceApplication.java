package com.mawai.wiibservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.mawai"})
@MapperScan({"com.mawai.wiibservice.mapper"})
@EnableScheduling
public class WiibServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WiibServiceApplication.class, args);
    }
}
