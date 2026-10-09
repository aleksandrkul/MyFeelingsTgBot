package com.myfeelings.diary;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The diary as one column: today's card at the top, everything earlier below it, grouped by month,
 * newest month first.
 *
 * <p>Renders into whole messages and never cuts a day in half: a day is the unit the owner reads.
 */
public class Feed {

    private final DiaryRepository repository;
    private final Messages messages;

    public Feed(DiaryRepository repository, Messages messages) {
        this.repository = repository;
        this.messages = messages;
    }

    /**
     * The feed from {@code from} to {@code today}, as messages ready to send.
     *
     * <p>Empty when there is nothing in the period; the caller decides what to say about that.
     */
    public List<String> render(LocalDate from, LocalDate today) throws SQLException {
        List<Entry> entries = repository.findBetween(from, today);
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, DayCard> cards = repository.cardsBetween(from, today);

        // Newest first, which is the order the column is read in.
        Map<LocalDate, List<Entry>> byDate = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byDate.computeIfAbsent(entry.entryDate(), d -> new ArrayList<>()).add(entry);
        }
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        dates.sort((a, b) -> b.compareTo(a));

        List<String> blocks = new ArrayList<>();
        YearMonth openMonth = null;
        for (LocalDate date : dates) {
            DayCard card = cards.getOrDefault(date, DayCard.empty(date));
            if (date.equals(today)) {
                blocks.add(today(date, byDate.get(date), card));
                continue;
            }
            YearMonth month = YearMonth.from(date);
            if (!month.equals(openMonth)) {
                openMonth = month;
                blocks.add(monthHeader(month));
            }
            blocks.add(earlierDay(date, byDate.get(date), card));
        }
        blocks.add(messages.get("feed.tail",
                messages.plural("day", dates.size()), messages.plural("entry", entries.size())));
        return pack(blocks);
    }

    /** Today in full, with the time each piece was written: that is what still needs reading closely. */
    private String today(LocalDate date, List<Entry> entries, DayCard card) {
        StringBuilder out = new StringBuilder(messages.get("feed.today",
                date.format(DateTimeFormatter.ofPattern("d MMMM", messages.locale()))));
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
        for (Entry entry : entries) {
            out.append('\n').append(entry.createdAt().atZone(Config.ZONE).format(time))
                    .append("  ").append(entry.text());
        }
        return out.append(marks(card)).toString();
    }

    /** An earlier day: the date, its text, and the two marks. No times — they are noise by then. */
    private String earlierDay(LocalDate date, List<Entry> entries, DayCard card) {
        StringBuilder out = new StringBuilder(
                date.format(DateTimeFormatter.ofPattern("d MMMM", messages.locale())));
        for (Entry entry : entries) {
            out.append('\n').append("  ").append(entry.text());
        }
        return out.append(marks(card)).toString();
    }

    /** The feeling and the conversation mark on one line, or nothing when neither was set. */
    private String marks(DayCard card) {
        List<String> parts = new ArrayList<>();
        card.feelingIfSet().ifPresent(feeling -> parts.add(messages.get(feeling.messageKey())));
        if (card.talked() != null) {
            parts.add(messages.get(card.talked() ? "feed.talked.yes" : "feed.talked.no"));
        }
        return parts.isEmpty() ? "" : "\n" + String.join(" · ", parts);
    }

    /** Nominative month name, which is why the pattern uses LLLL rather than MMMM. */
    private String monthHeader(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, messages.locale());
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + month.getYear();
    }

    /**
     * Fills messages up to the Telegram limit, starting a new one before a block that would not fit.
     * A single block longer than the limit is the only case that gets cut, and then on a line break.
     */
    private List<String> pack(List<String> blocks) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (block.length() > Telegram.MESSAGE_LIMIT) {
                flush(out, current);
                out.addAll(Telegram.split(block, Telegram.MESSAGE_LIMIT));
                continue;
            }
            // +2 for the blank line that separates blocks.
            if (!current.isEmpty() && current.length() + 2 + block.length() > Telegram.MESSAGE_LIMIT) {
                flush(out, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        flush(out, current);
        return out;
    }

    private void flush(List<String> out, StringBuilder current) {
        if (!current.isEmpty()) {
            out.add(current.toString());
            current.setLength(0);
        }
    }
}
