package dev.c4lam.celeste.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded immutable LRC timeline. Parsing and word segmentation happen on the lyric worker. */
public record TimedLyrics(List<Line> lines) {
    public static final TimedLyrics EMPTY = new TimedLyrics(List.of());
    public static final int MAX_LINES = 1500, MAX_TEXT = 180;
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern WORD_STAMP = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d{1,7})]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cntrl}\\u00a7]");
    private static final Pattern WORD = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);
    public TimedLyrics {
        List<Line> sorted = new ArrayList<>();
        if (lines != null) for (Line line : lines) {
            if (line != null) sorted.add(line);
            if (sorted.size() == MAX_LINES) break;
        }
        sorted.sort(Comparator.comparingLong(Line::timeMs));
        List<Line> unique = new ArrayList<>(sorted.size());
        for (Line line : sorted) {
            if (!unique.isEmpty() && unique.getLast().timeMs() == line.timeMs()) unique.set(unique.size() - 1, line);
            else unique.add(line);
        }
        List<Line> prepared = new ArrayList<>(unique.size());
        for (int i = 0; i < unique.size(); i++) {
            Line line = unique.get(i);
            long end = endOf(unique, i);
            List<Word> words = validWords(line, end) ? line.words() : timedWords(line.text(), line.timeMs(), end, line.stamps());
            prepared.add(new Line(line.timeMs(), line.text(), words, line.stamps()));
        }
        lines = List.copyOf(prepared);
    }
    /** Existing two-argument producers remain supported; their within-line timing is estimated. */
    public record Line(long timeMs, String text, List<Word> words, List<WordStamp> stamps) {
        public Line(long timeMs, String text) { this(timeMs, text, List.of(), List.of()); }
        public Line(long timeMs, String text, List<Word> words) { this(timeMs, text, words, List.of()); }
        public Line {
            timeMs = Math.max(0, timeMs);
            String cleaned = clean(text);
            words = words == null || !cleaned.equals(text) || words.size() > MAX_TEXT ? List.of() : List.copyOf(words);
            stamps = stamps == null || !cleaned.equals(text) || stamps.size() > MAX_TEXT * 2 ? List.of() : List.copyOf(stamps);
            int previousOffset = 0;
            long previousTime = 0;
            for (WordStamp stamp : stamps) {
                if (stamp.offset() < previousOffset || stamp.offset() > cleaned.length() || stamp.timeMs() < previousTime) {
                    stamps = List.of();
                    break;
                }
                previousOffset = stamp.offset();
                previousTime = stamp.timeMs();
            }
            text = cleaned;
        }
    }
    public record Cue(String current, String next, double progress) {
        public static final Cue EMPTY = new Cue("", "", 0);
    }
    /** UTF-16 [startChar,endChar) offsets into WordCue.text; all times use the media clock. */
    public record Word(int startChar, int endChar, long startMs, long endMs, boolean estimated) {
        public double progress(long playbackMs) { return fraction(playbackMs, startMs, endMs); }
        public int startOffset() { return startChar; }
        public int endOffset() { return endChar; }
    }
    /** ID is the unique timestamp, not text: repeated identical lines have distinct IDs. */
    public record WordCue(long id, long startMs, long endMs, String text, List<Word> words, boolean estimated) {
        public static final WordCue EMPTY = new WordCue(-1, 0, 0, "", List.of(), false);
        public WordCue { words = List.copyOf(words); }
        public double progress(long playbackMs) { return text.isEmpty() ? 0 : fraction(playbackMs, startMs, endMs); }
    }
    /** Supplied enhanced-LRC anchors retained so a known track end can re-time only inferred intervals. */
    public record WordStamp(int offset, long timeMs) { }
    private record Parsed(long timeMs, String text, List<WordStamp> marks) { }
    private record Token(int start, int end, double weight) { }
    public static TimedLyrics parse(String lrc) {
        if (lrc == null || lrc.length() > 512_000) return EMPTY;
        long offset = 0;
        var offsetMatch = OFFSET.matcher(lrc);
        if (offsetMatch.find()) offset = Long.parseLong(offsetMatch.group(1));
        List<Parsed> parsed = new ArrayList<>();
        for (String row : lrc.split("\\R", 6000)) {
            var match = STAMP.matcher(row);
            List<Long> times = new ArrayList<>(2);
            int end = 0;
            while (match.find() && times.size() < 32) {
                long stamp = stamp(match.group(1), match.group(2), match.group(3));
                if (stamp < 0) continue;
                times.add(stamp);
                end = match.end();
            }
            if (times.isEmpty()) continue;
            String body = row.substring(end);
            var wordMatch = WORD_STAMP.matcher(body);
            StringBuilder plain = new StringBuilder();
            List<WordStamp> rawMarks = new ArrayList<>();
            boolean valid = true;
            int cursor = 0;
            long lastTime = -1;
            while (wordMatch.find()) {
                plain.append(CONTROLS.matcher(body.substring(cursor, wordMatch.start())).replaceAll(""));
                long time = stamp(wordMatch.group(1), wordMatch.group(2), wordMatch.group(3));
                if (time < 0 || time < lastTime || rawMarks.size() >= MAX_TEXT * 2) valid = false;
                else rawMarks.add(new WordStamp(plain.length(), time));
                lastTime = time;
                cursor = wordMatch.end();
            }
            plain.append(CONTROLS.matcher(body.substring(cursor)).replaceAll(""));
            String untrimmed = plain.toString();
            int leading = untrimmed.length() - untrimmed.stripLeading().length();
            String stripped = untrimmed.strip();
            String value = clean(untrimmed);
            boolean truncated = stripped.codePointCount(0, stripped.length()) > MAX_TEXT;
            int retained = truncated ? value.length() - 1 : value.length();
            for (long time : times) {
                if (parsed.size() == MAX_LINES) break;
                List<WordStamp> marks = new ArrayList<>(rawMarks.size());
                if (valid) for (WordStamp mark : rawMarks) {
                    int at = mark.offset() - leading;
                    // Do not move timestamps for truncated-away words onto the visible ellipsis.
                    if (!truncated || at <= retained) marks.add(new WordStamp(Math.clamp(at, 0, value.length()),
                        Math.max(0, mark.timeMs() + time - times.getFirst() - offset)));
                }
                parsed.add(new Parsed(Math.max(0, time - offset), value, marks));
            }
            if (parsed.size() == MAX_LINES) break;
        }
        parsed.sort(Comparator.comparingLong(Parsed::timeMs));
        // Duplicate timestamps choose the last supplied line deterministically.
        List<Parsed> unique = new ArrayList<>(parsed.size());
        for (Parsed line : parsed) {
            if (!unique.isEmpty() && unique.getLast().timeMs() == line.timeMs()) unique.set(unique.size() - 1, line);
            else unique.add(line);
        }
        List<Line> result = new ArrayList<>(unique.size());
        for (int i = 0; i < unique.size(); i++) {
            Parsed line = unique.get(i);
            long end = i + 1 < unique.size() ? Math.min(unique.get(i + 1).timeMs(), plus(line.timeMs(), 16_000)) : plus(line.timeMs(), 8000);
            result.add(new Line(line.timeMs(), line.text(), timedWords(line.text(), line.timeMs(), end, line.marks()), line.marks()));
        }
        return result.isEmpty() ? EMPTY : new TimedLyrics(result);
    }
    /** Pure lookup: pausing the media clock freezes all words; seeking carries no previous progress. */
    public WordCue wordCue(long positionMs, long durationMs) {
        if (durationMs > 0 && positionMs >= durationMs) return WordCue.EMPTY;
        int found = find(positionMs);
        if (found < 0) return WordCue.EMPTY;
        Line line = lines.get(found);
        long end = endOf(lines, found);
        if (durationMs > 0) end = Math.min(end, durationMs);
        if (positionMs >= end || line.text().isBlank()) return WordCue.EMPTY;
        List<Word> words = line.words();
        if (words.isEmpty()) return WordCue.EMPTY;
        if (!words.isEmpty() && words.getLast().endMs() > end) {
            // Re-estimate within the real remaining duration, retaining every in-bounds supplied anchor.
            // Merely clamping would pile later inferred words onto track end without visible progression.
            words = timedWords(line.text(), line.timeMs(), end, line.stamps());
        }
        boolean estimated = false;
        for (Word word : words) estimated |= word.estimated();
        return new WordCue(line.timeMs(), line.timeMs(), end, line.text(), words, estimated);
    }
    public Cue cue(long positionMs, long durationMs) {
        // Native progress stops at duration; a stray later LRC stamp must not keep a finished song visible.
        if (durationMs > 0 && positionMs >= durationMs) return Cue.EMPTY;
        int found = find(positionMs);
        if (found < 0) return new Cue("", lines.isEmpty() ? "" : lines.getFirst().text(), 0);
        Line line = lines.get(found);
        long end = found + 1 < lines.size() ? lines.get(found + 1).timeMs()
            : Math.min(durationMs > line.timeMs() ? durationMs : Long.MAX_VALUE, plus(line.timeMs(), 8000));
        if (positionMs >= end || positionMs - line.timeMs() > 16_000) return Cue.EMPTY;
        double progress = fraction(positionMs, line.timeMs(), end);
        return new Cue(line.text(), found + 1 < lines.size() ? lines.get(found + 1).text() : "", progress);
    }
    private int find(long positionMs) {
        int low = 0, high = lines.size() - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs() <= positionMs) { found = middle; low = middle + 1; }
            else high = middle - 1;
        }
        return found;
    }
    private static long endOf(List<Line> lines, int index) {
        long start = lines.get(index).timeMs();
        return index + 1 < lines.size() ? Math.min(lines.get(index + 1).timeMs(), plus(start, 16_000)) : plus(start, 8000);
    }
    private static long plus(long value, long amount) { return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount; }
    private static double fraction(long value, long start, long end) {
        if (value < start) return 0;
        if (value >= end) return 1;
        return (value - start) / (double) Math.max(1, end - start);
    }
    private static long stamp(String minutes, String seconds, String fraction) {
        int sec = Integer.parseInt(seconds);
        if (sec >= 60) return -1;
        long millis = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
        return Long.parseLong(minutes) * 60_000 + sec * 1000L + millis;
    }
    private static List<Word> timedWords(String text, long start, long end, List<WordStamp> marks) {
        List<Token> tokens = new ArrayList<>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            // Deterministic reading-time estimate, not detected vocals. No token can dominate a line.
            double weight = Math.clamp(Math.sqrt(token.codePointCount(0, token.length())), 1, 3.5);
            if (".,;:!?\u3002\uff01\uff1f\uff0c".indexOf(token.codePointBefore(token.length())) >= 0) weight += .35;
            tokens.add(new Token(matcher.start(), matcher.end(), weight));
        }
        int count = tokens.size();
        if (count == 0) return List.of();
        long[] anchors = new long[count + 1];
        long[] explicitEnds = new long[count];
        Arrays.fill(anchors, -1);
        Arrays.fill(explicitEnds, -1);
        boolean[] explicit = new boolean[count + 1];
        boolean[] endSupplied = new boolean[count];
        for (WordStamp mark : marks) {
            int index = 0;
            while (index < count && tokens.get(index).end() <= mark.offset()) index++;
            long time = Math.clamp(mark.timeMs(), start, end);
            boolean inBounds = mark.timeMs() >= start && mark.timeMs() <= end;
            if (index > 0 && tokens.get(index - 1).end() == mark.offset()) {
                explicitEnds[index - 1] = time;
                endSupplied[index - 1] = inBounds;
            }
            // Multiple syllable stamps in one word retain its earliest start.
            // Separate end/start stamps around whitespace preserve an actual inter-word pause.
            if (anchors[index] >= 0 && (index == count || mark.offset() > tokens.get(index).start())) continue;
            anchors[index] = time;
            explicit[index] = inBounds
                && (index == count || mark.offset() <= tokens.get(index).start());
        }
        if (anchors[0] < 0) anchors[0] = start;
        if (anchors[count] < 0) anchors[count] = end;
        List<Word> result = new ArrayList<>(count);
        int first = 0;
        while (first < count) {
            int last = first + 1;
            while (anchors[last] < 0) last++;
            long from = anchors[first], until = Math.max(from, explicitEnds[last - 1] >= 0 ? explicitEnds[last - 1] : anchors[last]);
            double total = 0, used = 0;
            for (int i = first; i < last; i++) total += tokens.get(i).weight();
            boolean estimated = last - first != 1 || !explicit[first]
                || !(explicitEnds[last - 1] >= 0 ? endSupplied[last - 1] : explicit[last]);
            long at = from;
            for (int i = first; i < last; i++) {
                Token token = tokens.get(i);
                used += token.weight();
                long next = i + 1 == last ? until : from + Math.round((until - from) * (used / total));
                result.add(new Word(token.start(), token.end(), at, next, estimated));
                at = next;
            }
            first = last;
        }
        return List.copyOf(result);
    }
    private static boolean validWords(Line line, long end) {
        if (line.words().isEmpty()) return line.text().isBlank();
        int previousChar = 0;
        long previousTime = line.timeMs();
        for (Word word : line.words()) {
            if (word.startChar() < previousChar || word.endChar() <= word.startChar() || word.endChar() > line.text().length()
                || word.startMs() < previousTime || word.endMs() < word.startMs() || word.endMs() > end
                || splitSurrogate(line.text(), word.startChar()) || splitSurrogate(line.text(), word.endChar())) return false;
            previousChar = word.endChar();
            previousTime = word.endMs();
        }
        return true;
    }
    private static boolean splitSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }
    public static String clean(String input) {
        if (input == null) return "";
        String clean = CONTROLS.matcher(input).replaceAll("").strip();
        int count = clean.codePointCount(0, clean.length());
        return count <= MAX_TEXT ? clean : clean.substring(0, clean.offsetByCodePoints(0, MAX_TEXT - 1)) + "\u2026";
    }
}
