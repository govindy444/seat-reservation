package com.example.seats;

import org.springframework.boot.SpringApplication;

public class TestSeatReservationApplication {

	public static void main(String[] args) {
		SpringApplication.from(SeatReservationApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
