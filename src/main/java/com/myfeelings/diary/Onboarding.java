package com.myfeelings.diary;

import java.sql.SQLException;
import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * The one-time setup that follows the language: how to address the owner, and who the diary is
 * about. {@code /who} runs it again to change them.
 *
 * <p>The two are asked once and never again, so the whole state of this conversation is derived from
 * what is already stored, plus the little that cannot be: whether the question has actually been put
 * to the owner yet.
 */
class Onboarding {

    /** A name is one short message, not an essay. */
    private static final int NAME_LIMIT = 64;

    private enum Step { OWNER_NAME, PERSON_NAME, DONE }

    private final DiaryRepository repository;
    private final Settings settings;
    private final Messages messages;
    private final Telegram telegram;

    private Step step;

    /**
     * True while {@code /who} is asking again. The first-time setup stores each answer as it comes,
     * so a restart resumes at the right question; a change through {@code /who} instead commits both
     * names together, so a restart in the middle of it leaves the old pair untouched rather than
     * half-replaced.
     */
    private boolean reAsking;

    private String pendingOwnerName;

    /**
     * Whether the question the setup is waiting on has actually been put to the owner. Without this
     * a database that has a language but no names yet — an older diary being upgraded — would take
     * the next diary message as the answer to a question it never asked.
     */
    private boolean asked;

    Onboarding(DiaryRepository repository, Messages messages, Telegram telegram) throws SQLException {
        this.repository = repository;
        this.settings = repository.settings();
        this.messages = messages;
        this.telegram = telegram;
        this.step = resume();
    }

    /** Where the setup stopped, so a restart continues rather than starting over. */
    private Step resume() throws SQLException {
        if (settings.ownerName().isEmpty()) {
            return Step.OWNER_NAME;
        }
        return settings.personName().isEmpty() ? Step.PERSON_NAME : Step.DONE;
    }

    boolean isDone() {
        return step == Step.DONE;
    }

    /** What the setup is waiting to hear, for the step it is on. */
    String prompt() {
        return messages.get(step == Step.OWNER_NAME
                ? "onboarding.owner.name" : "onboarding.person.name");
    }

    /** Puts the current question, and remembers that it was asked. */
    void ask(Long chatId) {
        telegram.send(chatId, prompt());
        asked = true;
    }

    /**
     * A message arriving while the setup is unfinished.
     *
     * <p>If the question has not been put yet, it is asked now and the message is kept as an entry:
     * a diary message must not vanish into a question nobody heard.
     */
    void handle(Message message, String text) throws SQLException {
        if (!asked) {
            if (!text.startsWith("/")) {
                Entry entry = repository.save(DiaryDay.today(), text);
                telegram.reply(message, messages.get("entry.saved", entry.entryDate()));
            }
            ask(message.getChatId());
            return;
        }
        if (text.startsWith("/")) {
            telegram.reply(message, messages.get("onboarding.in.progress", prompt()));
            return;
        }
        if (text.isBlank() || text.length() > NAME_LIMIT) {
            telegram.reply(message, messages.get("name.invalid", NAME_LIMIT));
            return;
        }
        accept(message, text);
    }

    /** The step advances on its own, not by re-reading the database: {@code /who} asks both again. */
    private void accept(Message message, String name) throws SQLException {
        if (step == Step.OWNER_NAME) {
            if (reAsking) {
                pendingOwnerName = name;
            } else {
                settings.saveOwnerName(name);
            }
            step = Step.PERSON_NAME;
            telegram.reply(message, prompt());
            return;
        }
        if (pendingOwnerName != null) {
            settings.saveOwnerName(pendingOwnerName);
        }
        settings.savePersonName(name);
        pendingOwnerName = null;
        reAsking = false;
        step = Step.DONE;
        // The menu is attached once the names are known, and not before: until then the keyboard
        // would offer a diary that is still asking who it is for.
        telegram.sendWithKeyboard(message.getChatId(), messages.get("onboarding.done",
                settings.ownerName().orElse(""), settings.personName().orElse("")), Menu.keyboard(messages));
    }

    /** {@code /who}: shows the two names and asks them again, which is how they are changed. */
    void restart(Message message) throws SQLException {
        telegram.reply(message, messages.get("who.current",
                settings.ownerName().orElse(""), settings.personName().orElse("")));
        reAsking = true;
        step = Step.OWNER_NAME;
        ask(message.getChatId());
    }
}
