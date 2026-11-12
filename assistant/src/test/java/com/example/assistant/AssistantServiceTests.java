package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class AssistantServiceTests {

	private static HelpdeskProperties props(int maxConcurrent) {
		return new HelpdeskProperties(null, null, null, 0, maxConcurrent);
	}

	@Test
	void returnsModelContent() {
		ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
		when(chat.prompt().user("hi").call().content()).thenReturn("hello");
		assertThat(new AssistantService(chat, props(2)).ask("hi")).isEqualTo("hello");
	}

	@Test
	void releasesPermitWhenModelFails() {
		ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
		when(chat.prompt().user("boom").call().content()).thenThrow(new IllegalStateException("down"));
		when(chat.prompt().user("ok").call().content()).thenReturn("fine");
		AssistantService service = new AssistantService(chat, props(1));
		assertThatThrownBy(() -> service.ask("boom")).isInstanceOf(IllegalStateException.class);
		// the single permit must be free again
		assertThat(service.ask("ok")).isEqualTo("fine");
	}

	@Test
	void rejectsWhenAllPermitsAreBusy() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
		when(chat.prompt().user("slow").call().content()).thenAnswer(inv -> {
			started.countDown();
			finish.await();
			return "done";
		});
		AssistantService service = new AssistantService(chat, props(1));
		AtomicReference<String> first = new AtomicReference<>();
		Thread t = new Thread(() -> first.set(service.ask("slow")));
		t.start();
		started.await();

		assertThatThrownBy(() -> service.ask("other")).isInstanceOf(TooManyRequestsException.class);

		finish.countDown();
		t.join();
		assertThat(first.get()).isEqualTo("done");
	}

	@Test
	void interruptedWaitIsTreatedAsBusyAndKeepsInterruptFlag() {
		AssistantService service = new AssistantService(mock(ChatClient.class), props(1));
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> service.ask("x")).isInstanceOf(TooManyRequestsException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted(); // clear flag for other tests
		}
	}

}
