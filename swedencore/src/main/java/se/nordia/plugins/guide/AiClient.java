package se.nordia.plugins.guide;

import java.util.List;

/** A language model the guide can ask. Blocking: call it off the main thread. */
public interface AiClient {

    /** One message in a conversation. */
    record Turn(Role role, String text) {
        public enum Role { USER, ASSISTANT }
    }

    /** The client for the provider the configured model belongs to. */
    static AiClient create(GuideConfig config) {
        return config.provider() == GuideConfig.Provider.ANTHROPIC ? new AnthropicClient(config) : new GeminiClient(config);
    }

    /** Sends the conversation (ending with a user turn) and returns the assistant's reply text. */
    String complete(String systemPrompt, List<Turn> conversation) throws AiException;

    /** The model could not be reached or refused. The message is for the server log, never for players. */
    class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }

        public AiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
