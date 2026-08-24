package dev.kousik.jobhunt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JobHuntEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(JobHuntEngineApplication.class, args);
	}

}
