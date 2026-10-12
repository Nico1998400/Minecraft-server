package se.nordia.plugins.guide;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.nordia.plugins.core.DomainException;
import se.nordia.plugins.localization.SupportedLocale;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuideServiceTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeAi implements AiClient {
        final List<String> systems = new ArrayList<>();
        final List<List<Turn>> conversations = new ArrayList<>();
        String answer = "Hej! Öppna /meny.";
        AiException failure;

        @Override
        public String complete(String systemPrompt, List<Turn> conversation) throws AiException {
            systems.add(systemPrompt);
            conversations.add(List.copyOf(conversation));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private final UUID player = UUID.randomUUID();
    private TestClock clock;
    private FakeAi ai;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        ai = new FakeAi();
    }

    private GuideService service(int dailyLimit, int historyTurns) {
        GuideConfig config = new GuideConfig(true, "Brage", "sk-test", GuideConfig.DEFAULT_MODEL, "",
                400, historyTurns, 100, Duration.ofSeconds(3), dailyLimit, Duration.ofSeconds(5));
        return new GuideService(config, ai, clock, "NORDIA knowledge", Logger.getAnonymousLogger());
    }

    private String ask(GuideService service, String text) {
        return service.reply(player, SupportedLocale.SV_SE, "- Balance: 1 000 kr", text);
    }

    @Test
    void answersAndPutsKnowledgeAndPlayerFactsInThePrompt() {
        GuideService service = service(100, 10);
        assertThat(ask(service, "Hur får jag jobb?")).isEqualTo("Hej! Öppna /meny.");
        assertThat(ai.systems.getFirst()).contains("NORDIA knowledge", "Balance: 1 000 kr", "Swedish", "Brage");
        assertThat(ai.conversations.getFirst()).containsExactly(new AiClient.Turn(AiClient.Turn.Role.USER, "Hur får jag jobb?"));
    }

    @Test
    void englishPlayersGetAnEnglishPrompt() {
        GuideService service = service(100, 10);
        service.reply(player, SupportedLocale.EN_US, "", "hi");
        assertThat(ai.systems.getFirst()).contains("Reply in English");
    }

    @Test
    void remembersEarlierTurnsButOnlyTheLatestOnes() {
        GuideService service = service(100, 2);
        for (int i = 1; i <= 4; i++) {
            ask(service, "fråga " + i);
            clock.advance(Duration.ofSeconds(5));
        }
        List<AiClient.Turn> last = ai.conversations.getLast();
        // maxHistoryTurns=2 keeps the last two question/answer pairs; the 4th question therefore starts at question 2
        assertThat(last).hasSize(5);
        assertThat(last.getFirst().text()).isEqualTo("fråga 2");
        ask(service, "fråga 5");
        assertThat(ai.conversations.getLast()).hasSize(5).first().extracting(AiClient.Turn::text).isEqualTo("fråga 3");
    }

    @Test
    void rejectsQuestionsTooFastAndAcceptsThemAfterTheInterval() {
        GuideService service = service(100, 10);
        ask(service, "ett");
        assertThatThrownBy(() -> ask(service, "två")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("guide_slow_down"));
        clock.advance(Duration.ofSeconds(3));
        assertThat(ask(service, "två")).isNotBlank();
        assertThat(ai.conversations).hasSize(2);
    }

    @Test
    void enforcesTheDailyLimitAndResetsItTheNextDay() {
        GuideService service = service(2, 10);
        ask(service, "ett");
        clock.advance(Duration.ofSeconds(5));
        ask(service, "två");
        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> ask(service, "tre")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("guide_daily_limit"));
        clock.advance(Duration.ofDays(1));
        assertThat(ask(service, "tre")).isNotBlank();
    }

    @Test
    void aFailingModelIsReportedWithoutLeakingDetailsAndDoesNotBlockThePlayer() {
        GuideService service = service(100, 10);
        ai.failure = new AiClient.AiException("HTTP 401: invalid x-api-key sk-secret");
        assertThatThrownBy(() -> ask(service, "hej")).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.code()).isEqualTo("guide_ai_failed");
            assertThat(e.getMessage()).doesNotContain("sk-secret");
        });
        ai.failure = null;
        clock.advance(Duration.ofSeconds(5));
        assertThat(ask(service, "hej igen")).isNotBlank();
        // the failed question was not remembered
        assertThat(ai.conversations.getLast()).hasSize(1);
    }

    @Test
    void rejectsEmptyAndTooLongMessagesWithoutCallingTheModel() {
        GuideService service = service(100, 10);
        assertThatThrownBy(() -> ask(service, "   ")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("guide_empty"));
        assertThatThrownBy(() -> ask(service, "x".repeat(101))).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("guide_too_long"));
        assertThat(ai.conversations).isEmpty();
    }

    @Test
    void withoutAKeyTheGuideIsUnavailableAndNeverCallsOut() {
        GuideConfig noKey = new GuideConfig(true, "Brage", "", null, null, 400, 10, 100, Duration.ofSeconds(3), 100,
                Duration.ofSeconds(5));
        GuideService service = new GuideService(noKey, ai, clock, "k", Logger.getAnonymousLogger());
        assertThat(service.aiAvailable()).isFalse();
        assertThatThrownBy(() -> ask(service, "hej")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.code()).isEqualTo("guide_unavailable"));
        assertThat(ai.conversations).isEmpty();
    }

    @Test
    void repliesAreStrippedOfControlCharactersAndCapped() {
        assertThat(GuideService.clean("a\u0000b\u001b[31m\nc\t d")).isEqualTo("ab[31m c d");
        assertThat(GuideService.clean("y".repeat(5_000))).hasSizeLessThanOrEqualTo(1_201);
    }

    @Test
    void theApiKeyNeverShowsUpInToString() {
        GuideConfig config = new GuideConfig(true, "Brage", "sk-ant-very-secret", null, null, 400, 10, 100,
                Duration.ofSeconds(3), 100, Duration.ofSeconds(5));
        assertThat(config.toString()).doesNotContain("very-secret").contains("<hidden>");
    }

    @Test
    void theKeyIsReadFromTheEnvironmentWithAFallbackName() {
        assertThat(GuideConfig.keyFromEnvironment(name -> name.equals("NORDIA_AI_API_KEY") ? " k1 " : "other")).isEqualTo("k1");
        assertThat(GuideConfig.keyFromEnvironment(name -> name.equals("ANTHROPIC_API_KEY") ? "k2" : null)).isEqualTo("k2");
        assertThat(GuideConfig.keyFromEnvironment(name -> null)).isEmpty();
    }
}
