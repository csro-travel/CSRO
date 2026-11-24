package com.csro.backend;

import com.csro.backend.cases.LocalChatDemo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		var application = new SpringApplication(BackendApplication.class);
		application.addInitializers(LocalChatDemo::configure);
		application.run(args);
	}

}
