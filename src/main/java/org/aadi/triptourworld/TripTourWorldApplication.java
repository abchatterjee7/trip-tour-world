package org.aadi.triptourworld;

import org.aadi.triptourworld.config.DotenvLoader;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TripTourWorldApplication {

    public static void main(String[] args) {
        DotenvLoader.load(Path.of(".env"), Path.of("/app/.env"));
        SpringApplication.run(TripTourWorldApplication.class, args);
    }
}
