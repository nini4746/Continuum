package com.continuum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ContinuumApp {
    public static void main(String[] args) {
        SpringApplication.run(ContinuumApp.class, args);
    }
}
