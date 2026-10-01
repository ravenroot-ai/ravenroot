package ai.ravenroot.core.runtime.builtin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.zone.ZoneRules;
import java.util.*;

/** Pure, bounded calendar matching shared by the two core scheduled sources. */
sealed interface ScheduleDefinition permits ScheduleDefinition.Timer, ScheduleDefinition.Crontab {
    int MAX_LOOKAHEAD_YEARS = 8;

    ZoneId zone();
    Occurrence nextAfter(Instant after);
    Occurrence previousAtOrBefore(Instant at);
    String fingerprint();

    record Occurrence(Instant instant, LocalDateTime local, ZoneOffset offset, String selector) {
        public Occurrence {
            Objects.requireNonNull(instant); Objects.requireNonNull(local);
            Objects.requireNonNull(offset); Objects.requireNonNull(selector);
        }
    }

    static ZoneId parseZone(String value) {
        try {
            ZoneId zone = ZoneId.of(Objects.requireNonNull(value, "zoneId").strip());
            if (!ZoneId.getAvailableZoneIds().contains(zone.getId())) {
                throw new IllegalArgumentException("invalid zoneId");
            }
            return zone;
        } catch (DateTimeException | NullPointerException invalid) {
            throw new IllegalArgumentException("invalid zoneId");
        }
    }

    static List<Occurrence> occurrences(LocalDate date, LocalTime time, ZoneId zone, String selector) {
        LocalDateTime local = LocalDateTime.of(date, time);
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.isEmpty()) return List.of(); // DST gap.
        return offsets.stream().map(offset -> new Occurrence(local.toInstant(offset), local, offset, selector))
                .sorted(Comparator.comparing(Occurrence::instant)).toList();
    }

    static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    final class Timer implements ScheduleDefinition {
        static final int MAX_TIMES = 64;
        private final ZoneId zone;
        private final List<LocalTime> times;
        private final Set<DayOfWeek> weekdays;
        private final String fingerprint;

        Timer(String zoneId, String rawTimes, String rawWeekdays) {
            zone = parseZone(zoneId);
            var parsedTimes = new TreeSet<LocalTime>();
            for (String item : Objects.requireNonNull(rawTimes, "times").split(",", -1)) {
                if (item.isBlank()) throw new IllegalArgumentException("empty timer time");
                try {
                    LocalTime value = LocalTime.parse(item.strip(), DateTimeFormatter.ISO_LOCAL_TIME);
                    if (value.getNano() != 0) throw new IllegalArgumentException("fractional seconds unsupported");
                    parsedTimes.add(value);
                } catch (DateTimeParseException invalid) {
                    throw new IllegalArgumentException("invalid timer time", invalid);
                }
            }
            if (parsedTimes.isEmpty() || parsedTimes.size() > MAX_TIMES) {
                throw new IllegalArgumentException("timer time count outside bound");
            }
            times = List.copyOf(parsedTimes);
            var parsedDays = EnumSet.noneOf(DayOfWeek.class);
            String days = rawWeekdays == null || rawWeekdays.isBlank()
                    ? "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY" : rawWeekdays;
            for (String item : days.split(",", -1)) {
                try { parsedDays.add(DayOfWeek.valueOf(item.strip())); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid weekday", invalid); }
            }
            if (parsedDays.isEmpty()) throw new IllegalArgumentException("weekdays cannot be empty");
            weekdays = Set.copyOf(parsedDays);
            fingerprint = digest("timer\0" + zone.getId() + "\0" + times + "\0" + weekdays.stream().sorted().toList());
        }

        @Override public ZoneId zone() { return zone; }
        @Override public String fingerprint() { return fingerprint; }

        @Override public Occurrence nextAfter(Instant after) {
            LocalDate start = after.atZone(zone).toLocalDate().minusDays(1);
            Occurrence best = null;
            for (int day = 0; day <= 8; day++) {
                LocalDate date = start.plusDays(day);
                if (!weekdays.contains(date.getDayOfWeek())) continue;
                for (LocalTime time : times) for (Occurrence candidate : occurrences(date, time, zone, time.toString())) {
                    if (candidate.instant().isAfter(after)
                            && (best == null || candidate.instant().isBefore(best.instant()))) best = candidate;
                }
                if (best != null && date.isAfter(best.local().toLocalDate())) break;
            }
            if (best == null) throw new IllegalStateException("timer has no occurrence within weekly bound");
            return best;
        }

        @Override public Occurrence previousAtOrBefore(Instant at) {
            LocalDate start = at.atZone(zone).toLocalDate().plusDays(1);
            Occurrence best = null;
            for (int day = 0; day <= 8; day++) {
                LocalDate date = start.minusDays(day);
                if (!weekdays.contains(date.getDayOfWeek())) continue;
                for (LocalTime time : times) for (Occurrence candidate : occurrences(date, time, zone, time.toString())) {
                    if (!candidate.instant().isAfter(at)
                            && (best == null || candidate.instant().isAfter(best.instant()))) best = candidate;
                }
                if (best != null && date.isBefore(best.local().toLocalDate())) break;
            }
            return best;
        }
    }

    final class Crontab implements ScheduleDefinition {
        static final int MAX_ENTRIES = 128;
        private final ZoneId zone;
        private final List<Entry> entries;
        private final String fingerprint;

        Crontab(String zoneId, String text) {
            zone = parseZone(zoneId);
            var parsed = new ArrayList<Entry>();
            String normalized = Objects.requireNonNull(text, "entries").replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n", -1);
            for (int line = 0; line < lines.length; line++) {
                String value = lines[line].strip();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (value.contains("#")) throw new IllegalArgumentException("inline cron comments unsupported");
                parsed.add(Entry.parse(value, line + 1));
                if (parsed.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many cron entries");
            }
            if (parsed.isEmpty()) throw new IllegalArgumentException("crontab has no entries");
            entries = List.copyOf(parsed);
            fingerprint = digest("crontab\0" + zone.getId() + "\0" + entries.stream().map(Entry::canonical).toList());
        }

        @Override public ZoneId zone() { return zone; }
        @Override public String fingerprint() { return fingerprint; }

        @Override public Occurrence nextAfter(Instant after) {
            LocalDate start = after.atZone(zone).toLocalDate().minusDays(1);
            Occurrence best = null;
            int days = 366 * MAX_LOOKAHEAD_YEARS + 3;
            for (int day = 0; day <= days; day++) {
                LocalDate date = start.plusDays(day);
                for (Entry entry : entries) {
                    if (!entry.matches(date)) continue;
                    for (int hour = entry.hours.nextSetBit(0); hour >= 0; hour = entry.hours.nextSetBit(hour + 1)) {
                        for (int minute = entry.minutes.nextSetBit(0); minute >= 0;
                             minute = entry.minutes.nextSetBit(minute + 1)) {
                            for (Occurrence candidate : occurrences(date, LocalTime.of(hour, minute), zone,
                                    Integer.toString(entry.line))) {
                                if (candidate.instant().isAfter(after)
                                        && (best == null || candidate.instant().isBefore(best.instant()))) best = candidate;
                            }
                        }
                    }
                }
                if (best != null && date.isAfter(best.local().toLocalDate())) break;
            }
            if (best == null) throw new IllegalStateException("crontab has no occurrence within lookahead bound");
            return best;
        }

        @Override public Occurrence previousAtOrBefore(Instant at) {
            LocalDate start = at.atZone(zone).toLocalDate().plusDays(1);
            Occurrence best = null;
            int days = 366 * MAX_LOOKAHEAD_YEARS + 3;
            for (int day = 0; day <= days; day++) {
                LocalDate date = start.minusDays(day);
                for (Entry entry : entries) {
                    if (!entry.matches(date)) continue;
                    for (int hour = entry.hours.length() - 1; hour >= 0; hour = entry.hours.previousSetBit(hour - 1)) {
                        for (int minute = entry.minutes.length() - 1; minute >= 0;
                             minute = entry.minutes.previousSetBit(minute - 1)) {
                            for (Occurrence candidate : occurrences(date, LocalTime.of(hour, minute), zone,
                                    Integer.toString(entry.line))) {
                                if (!candidate.instant().isAfter(at)
                                        && (best == null || candidate.instant().isAfter(best.instant()))) best = candidate;
                            }
                        }
                    }
                }
                if (best != null && date.isBefore(best.local().toLocalDate())) break;
            }
            return best;
        }

        private static final class Entry {
            private final int line;
            private final Field minutes, hours, monthDays, months, weekDays;
            private final String canonical;
            private Entry(int line, Field minutes, Field hours, Field monthDays, Field months,
                          Field weekDays, String canonical) {
                this.line = line; this.minutes = minutes; this.hours = hours; this.monthDays = monthDays;
                this.months = months; this.weekDays = weekDays; this.canonical = canonical;
            }
            static Entry parse(String line, int lineNumber) {
                String[] fields = line.split("\\s+");
                if (fields.length != 5) throw new IllegalArgumentException("cron line must contain five fields");
                return new Entry(lineNumber, Field.parse(fields[0], 0, 59, false),
                        Field.parse(fields[1], 0, 23, false), Field.parse(fields[2], 1, 31, false),
                        Field.parse(fields[3], 1, 12, false), Field.parse(fields[4], 0, 7, true),
                        String.join(" ", fields));
            }
            boolean matches(LocalDate date) {
                if (!months.get(date.getMonthValue())) return false;
                boolean dom = monthDays.get(date.getDayOfMonth());
                int cronDow = date.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : date.getDayOfWeek().getValue();
                boolean dow = weekDays.get(cronDow) || cronDow == 0 && weekDays.get(7);
                if (monthDays.wildcard && weekDays.wildcard) return true;
                if (monthDays.wildcard) return dow;
                if (weekDays.wildcard) return dom;
                return dom || dow;
            }
            String canonical() { return canonical; }
        }

        private static final class Field {
            private final BitSet values;
            private final boolean wildcard;
            private Field(BitSet values, boolean wildcard) { this.values = values; this.wildcard = wildcard; }
            int nextSetBit(int from) { return values.nextSetBit(from); }
            int previousSetBit(int from) { return values.previousSetBit(from); }
            int length() { return values.length(); }
            boolean get(int value) { return values.get(value); }

            static Field parse(String text, int min, int max, boolean sundayAlias) {
                if (!text.matches("[0-9*/,\\-]+")) throw new IllegalArgumentException("cron fields are numeric");
                BitSet values = new BitSet(max + 1);
                boolean wildcard = text.equals("*");
                for (String part : text.split(",", -1)) {
                    if (part.isEmpty()) throw new IllegalArgumentException("empty cron list item");
                    String[] stepParts = part.split("/", -1);
                    if (stepParts.length > 2 || stepParts.length == 2 && stepParts[1].isEmpty())
                        throw new IllegalArgumentException("invalid cron step");
                    int step = stepParts.length == 2 ? number(stepParts[1], 1, max - min + 1) : 1;
                    String range = stepParts[0];
                    int start, end;
                    if (range.equals("*")) { start = min; end = max; }
                    else if (range.contains("-")) {
                        String[] endpoints = range.split("-", -1);
                        if (endpoints.length != 2) throw new IllegalArgumentException("invalid cron range");
                        start = number(endpoints[0], min, max); end = number(endpoints[1], min, max);
                        if (start > end) throw new IllegalArgumentException("descending cron range");
                    } else {
                        start = number(range, min, max);
                        // Cron's N/step means N through the field maximum at that interval.
                        end = stepParts.length == 2 ? max : start;
                    }
                    for (int value = start; value <= end; value += step) values.set(value);
                }
                if (sundayAlias && values.get(7)) values.set(0);
                return new Field(values, wildcard);
            }
            private static int number(String text, int min, int max) {
                try {
                    if (!text.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
                    int value = Integer.parseInt(text);
                    if (value < min || value > max) throw new NumberFormatException();
                    return value;
                } catch (NumberFormatException invalid) { throw new IllegalArgumentException("cron value outside range"); }
            }
        }
    }
}
