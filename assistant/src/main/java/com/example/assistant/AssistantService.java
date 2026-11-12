package com.example.assistant;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/** Calls the LLM, with a cap on concurrent in-flight requests so slow model calls cannot pile up. */
@Service
class AssistantService {

	private static final long QUEUE_WAIT_MS = 1000;

	private final ChatClient chat;

	private final Semaphore permits;

	AssistantService(ChatClient chat, HelpdeskProperties props) {
		this.chat = chat;
		this.permits = new Semaphore(props.maxConcurrentRequests());
	}

	public String ask(String question) {
		try {
			if (!permits.tryAcquire(QUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
				throw new TooManyRequestsException();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TooManyRequestsException();
		}
		try {
			return chat.prompt().user(question).call().content();
		}
		finally {
			permits.release();
		}
	}

}
