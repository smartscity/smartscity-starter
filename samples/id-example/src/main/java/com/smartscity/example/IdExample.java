package com.smartscity.example;

import com.smartscity.id.api.IdGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class IdExample {
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = SpringApplication.run(IdExample.class, args)) {
            IdGenerator ids = context.getBean(IdGenerator.class);
            long id = ids.getUniqueId();
            System.out.println("ID=" + id + " worker=" + ids.parse(id).getWorkerId()
                    + " ready=" + ids.snapshot().isReady());
        }
    }
}
