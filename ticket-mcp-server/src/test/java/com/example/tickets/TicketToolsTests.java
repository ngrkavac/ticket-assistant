package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TicketToolsTests {

	private final TicketTools tools = new TicketTools();

	@Test
	void findsTicketCaseInsensitively() {
		assertThat(tools.getTicket(" t-1001 ").subject()).contains("VPN");
	}

	@Test
	void listsOnlyOpenTicketsOfCustomer() {
		assertThat(tools.listOpenTickets("acme")).extracting(Ticket::id).containsExactly("T-1001");
	}

	@Test
	void unknownTicketFails() {
		assertThatThrownBy(() -> tools.getTicket("T-9999")).hasMessageContaining("not found");
	}

	@Test
	void malformedIdIsRejected() {
		assertThatThrownBy(() -> tools.getTicket("1001; drop")).hasMessageContaining("Invalid ticket id");
		assertThatThrownBy(() -> tools.getTicket(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankCustomerIsRejected() {
		assertThatThrownBy(() -> tools.listOpenTickets(" ")).hasMessageContaining("required");
	}

}
