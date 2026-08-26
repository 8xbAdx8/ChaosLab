package com.chaoslab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChaosLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChaosLabApplication.class, args);
    }
}
