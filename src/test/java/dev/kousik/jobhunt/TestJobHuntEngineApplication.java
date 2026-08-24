package dev.kousik.jobhunt;

import org.springframework.boot.SpringApplication;

public class TestJobHuntEngineApplication {

	public static void main(String[] args) {
		SpringApplication.from(JobHuntEngineApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
