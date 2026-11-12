package com.example.assistant;

class TooManyRequestsException extends RuntimeException {

	TooManyRequestsException() {
		super("Too many concurrent requests");
	}

}
