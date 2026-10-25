package com.yoooche.points;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class UserPointsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserPointsServiceApplication.class, args);
	}

}
