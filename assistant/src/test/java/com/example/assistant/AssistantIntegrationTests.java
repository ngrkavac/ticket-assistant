package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Starts the whole application with fake models (no Ollama, no MCP server, no model download) to
 * verify the wiring: KB indexing, retrieval, and that retrieved context reaches the prompt.
 */
@SpringBootTest(
    properties = {
      "spring.ai.model.chat=none",
      "spring.ai.model.embedding=none",
      "spring.ai.mcp.client.enabled=false",
      "helpdesk.request-log.enabled=false",
      "management.server.port="
    })
@AutoConfigureMockMvc
class AssistantIntegrationTests {

  @Autowired MockMvc mvc;

  @Autowired VectorStore vectorStore;

  /** Tokens come from jwt(); the decoder only has to exist, as it does with OIDC_ISSUER_URI set. */
  @MockitoBean JwtDecoder jwtDecoder;

  /** There is no ticket server: the customer check is answered by this mock. */
  @MockitoBean TicketDirectoryClient tickets;

  @Test
  void knowledgeBaseIsIndexedAndSearchable() {
    List<Document> hits = vectorStore.similaritySearch("vpn drops tcp udp protocol");
    assertThat(hits).isNotEmpty();
    assertThat(hits.get(0).getText()).contains("VPN");
  }

  @Test
  void retrievedKnowledgeReachesThePrompt() throws Exception {
    // The fake chat model echoes the full prompt, so the answer shows what the LLM would have seen.
    given(tickets.requireKnown("acme")).willReturn("acme");
    mvc.perform(
            post("/ask")
                .with(TestUsers.agent("acme"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\":\"My vpn drops, which protocol should I use, udp or"
                        + " tcp?\",\"customer\":\"acme\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("UDP to TCP")));
  }

  @Test
  void theCustomerReachesTheModelOnlyThroughTheSystemPrompt() throws Exception {
    given(tickets.requireKnown("acme")).willReturn("acme");
    mvc.perform(
            post("/ask")
                .with(TestUsers.agent("acme"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"What open tickets do we have?\",\"customer\":\"acme\"}"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.answer")
                .value(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("You work for the customer \"acme\""),
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("Customer: acme")),
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("{customer}")))));
  }

  @Test
  void askingAboutAnotherCustomerIs403() throws Exception {
    mvc.perform(
            post("/ask")
                .with(TestUsers.agent("acme"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"globex\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Not allowed for this customer"));
  }

  @Test
  void askNeedsAToken() throws Exception {
    mvc.perform(
            post("/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
        .andExpect(status().isUnauthorized());
  }

  @TestConfiguration
  static class Fakes {

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /** Bag-of-words hashing embedding: texts sharing words are similar. */
    @Bean
    EmbeddingModel embeddingModel() {
      return new EmbeddingModel() {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
          List<org.springframework.ai.embedding.Embedding> out = new java.util.ArrayList<>();
          List<String> inputs = request.getInstructions();
          for (int i = 0; i < inputs.size(); i++) {
            out.add(new org.springframework.ai.embedding.Embedding(embed(inputs.get(i)), i));
          }
          return new EmbeddingResponse(out);
        }

        @Override
        public float[] embed(Document document) {
          return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
          float[] v = new float[128];
          var m = WORD.matcher(text.toLowerCase());
          while (m.find()) {
            v[Math.floorMod(m.group().hashCode(), 128)] += 1f;
          }
          return v;
        }
      };
    }

    @Bean
    ChatModel chatModel() {
      return prompt ->
          new ChatResponse(List.of(new Generation(new AssistantMessage(promptText(prompt)))));
    }

    private static String promptText(Prompt prompt) {
      return prompt.getContents();
    }
  }
}
