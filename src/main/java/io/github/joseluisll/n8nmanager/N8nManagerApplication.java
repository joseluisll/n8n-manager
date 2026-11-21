package io.github.joseluisll.n8nmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class N8nManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(N8nManagerApplication.class, args);
    }
}
