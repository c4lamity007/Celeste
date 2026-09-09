package dev.c4lam.celeste.media;

/** Original synthetic lyric fixtures only; no network, player session, or copyrighted song text. */
public final class SkyLyricsCheck {
    private static int checks;
    public static void main(String[] args) {
        var lyrics = TimedLyrics.parse("[ar:Example Artist]\n[00:02.5]A paper moon\n[00:04.25]Above the test world\n[00:06.125]A lantern turns\n");
        check(lyrics.lines().size() == 3, "metadata is ignored");
        check(lyrics.lines().get(0).timeMs() == 2500, "one digit fractions use tenths");
        check(lyrics.lines().get(1).timeMs() == 4250, "two digit fractions use hundredths");
        check(lyrics.lines().get(2).timeMs() == 6125, "three digit fractions use milliseconds");
        check(lyrics.cue(2499, 10000).current().isEmpty(), "no premature current line");
        check(lyrics.cue(2500, 10000).current().equals("A paper moon"), "exact cue start");
        close(lyrics.cue(3375, 10000).progress(), .5, "halfway progression");
        check(lyrics.cue(4250, 10000).current().equals("Above the test world"), "exact transition selects next line");
        check(lyrics.cue(10000, 10000).equals(TimedLyrics.Cue.EMPTY), "track end clears text");
        check(TimedLyrics.parse("[00:01]Before the endpoint\n[00:20]Beyond the track").cue(10000, 10000).equals(TimedLyrics.Cue.EMPTY),
            "known track end clears text even when later lyric timestamps exceed duration");
        check(lyrics.cue(3000, 10000).current().equals("A paper moon"), "backward seek recomputes from timeline");
        var repeat = TimedLyrics.parse("[00:02][00:05]A shared lantern\n[00:02.000]The later replacement\n[00:01]The first spark");
        check(repeat.lines().size() == 3, "duplicate timestamps collapse");
        check(repeat.lines().getFirst().timeMs() == 1000, "out-of-order rows sort by timestamp");
        check(repeat.cue(2000, 8000).current().equals("The later replacement"), "last supplied duplicate wins");
        check(repeat.cue(5000, 8000).current().equals("A shared lantern"), "multiple timestamps reuse row text");
        check(TimedLyrics.parse("[offset:+250]\n[00:01.000]Early lantern").lines().getFirst().timeMs() == 750,
            "positive LRC offset advances the cue");
        check(TimedLyrics.parse("[OFFSET:-250]\n[00:01]Late lantern").lines().getFirst().timeMs() == 1250,
            "negative offset delays cue, metadata case insensitive");
        check(TimedLyrics.parse("[offset:2000]\n[00:01]Before zero").lines().getFirst().timeMs() == 0, "offset clamps before zero");
        check(TimedLyrics.parse("[00:01]<00:01.000>One <00:01.500>small <00:02.000>orbit").lines().getFirst().text().equals("One small orbit"),
            "enhanced word stamps do not appear as text");
        check(TimedLyrics.parse("[00:99]Invalid seconds\n[no timestamp]\n[00:03]Valid").lines().size() == 1, "malformed stamps are ignored");
        check(TimedLyrics.parse(null) == TimedLyrics.EMPTY, "null input empty");
        check(TimedLyrics.parse(" ") == TimedLyrics.EMPTY, "blank input empty");
        check(TimedLyrics.parse("x".repeat(512001)) == TimedLyrics.EMPTY, "input hard limit");
        check(TimedLyrics.parse("[00:01]Long wait\n[01:00]A new spark").cue(18000, 90000).equals(TimedLyrics.Cue.EMPTY),
            "stale lyric clears across long instrumental gap");
        check(TimedLyrics.parse("[00:01]Short ending").cue(8999, 0).current().equals("Short ending"), "unknown duration bounded final hold");
        check(TimedLyrics.parse("[00:01]Short ending").cue(9000, 0).equals(TimedLyrics.Cue.EMPTY), "unknown duration hold expires");
        String emoji = "\uD83C\uDF19".repeat(240);
        String trimmed = TimedLyrics.clean(emoji);
        check(trimmed.codePointCount(0, trimmed.length()) == TimedLyrics.MAX_TEXT, "Unicode truncation counts codepoints");
        check(trimmed.endsWith("\u2026"), "truncation marked with ellipsis");
        check(!Character.isHighSurrogate(trimmed.charAt(trimmed.length()-2)), "truncation never splits surrogate pairs");
        check(TimedLyrics.clean("  a\u0000b\u00a7  ").equals("ab"), "control and formatting introducers removed");
        StringBuilder large = new StringBuilder();
        for (int i=0; i<2000; i++) large.append(String.format(java.util.Locale.ROOT,"[%02d:%02d]Synthetic line %d%n",i/60,i%60,i));
        check(TimedLyrics.parse(large.toString()).lines().size() == TimedLyrics.MAX_LINES, "timeline line cap");
        try { lyrics.lines().clear(); throw new AssertionError("mutable timeline"); }
        catch (UnsupportedOperationException expected) { checks++; }
        wordTimingChecks();
        System.out.println("SkyLyricsCheck PASS: " + checks + " parser/timeline assertions; all fixtures synthetic and offline");
    }
    private static void wordTimingChecks() {
        var exact = TimedLyrics.parse("[00:01]<00:01.000>One <00:01.500>small <00:02.000>orbit<00:02.750>\n[00:04]Next lantern");
        var cue = exact.wordCue(1250, 10000);
        check(cue.text().equals("One small orbit") && cue.words().size() == 3, "single-line word text and count");
        check(cue.id() == 1000 && cue.startMs() == 1000 && cue.endMs() == 4000, "stable exact cue boundaries");
        check(!cue.estimated() && cue.words().stream().noneMatch(TimedLyrics.Word::estimated), "complete enhanced boundaries explicitly exact");
        check(cue.words().get(0).startMs() == 1000 && cue.words().get(0).endMs() == 1500, "first supplied interval retained");
        check(cue.words().get(1).startMs() == 1500 && cue.words().get(1).endMs() == 2000, "middle supplied interval retained");
        check(cue.words().get(2).startMs() == 2000 && cue.words().get(2).endMs() == 2750, "trailing enhanced stamp is a real word end");
        close(cue.words().getFirst().progress(1250), .5, "word halfway progress");
        close(cue.words().getFirst().progress(Long.MIN_VALUE), 0, "word negative/overflow-sized clock clamps");
        close(cue.words().getFirst().progress(Long.MAX_VALUE), 1, "word future/overflow-sized clock clamps");
        close(cue.progress(2500), .5, "stable cue progress uses cue boundaries");
        check(exact.wordCue(1250, 10000).equals(cue), "paused media clock freezes cue and words");
        check(exact.wordCue(2500, 10000).words().equals(cue.words()), "word layout data is independent of playback time");
        check(exact.wordCue(1250, 10000).words() == cue.words(), "normal lookup reuses immutable precomputed words");
        check(exact.wordCue(999, 10000).equals(TimedLyrics.WordCue.EMPTY), "word cue absent before first stamp");
        close(TimedLyrics.WordCue.EMPTY.progress(0), 0, "empty cue has no active progress");
        check(exact.wordCue(10000, 10000).equals(TimedLyrics.WordCue.EMPTY), "word cue absent at track end");
        check(exact.wordCue(1250, 10000).equals(cue), "backward seek restores exact prior timing without accumulated state");
        for (var word : cue.words()) {
            check(word.startOffset() == word.startChar() && word.endOffset() == word.endChar(), "renderer offset aliases agree");
            check(!cue.text().substring(word.startChar(), word.endChar()).contains(" "), "offsets address text only, not adjoining spaces");
        }
        try { cue.words().clear(); throw new AssertionError("mutable word cue"); }
        catch (UnsupportedOperationException expected) { checks++; }
        try { exact.lines().getFirst().stamps().clear(); throw new AssertionError("mutable enhanced anchors"); }
        catch (UnsupportedOperationException expected) { checks++; }

        var estimated = TimedLyrics.parse("[00:01]I glow softly\n[00:05]New arc").wordCue(1000, 10000);
        check(estimated.estimated() && estimated.words().stream().allMatch(TimedLyrics.Word::estimated), "line-only LRCLIB explicitly estimated, never exact vocals");
        check(estimated.words().getFirst().startMs() == 1000 && estimated.words().getLast().endMs() == 5000, "estimates span actual line interval");
        long shortLength = estimated.words().getFirst().endMs() - estimated.words().getFirst().startMs();
        long longLength = estimated.words().getLast().endMs() - estimated.words().getLast().startMs();
        check(longLength > shortLength && longLength < shortLength * 4, "deterministic bounded token weighting");
        var boundedWeight = TimedLyrics.parse("[00:01]a " + "z".repeat(100) + "\n[00:11]Next").wordCue(1000, 20000);
        long brief = boundedWeight.words().getFirst().endMs() - boundedWeight.words().getFirst().startMs();
        long longToken = boundedWeight.words().getLast().endMs() - boundedWeight.words().getLast().startMs();
        check(longToken <= brief * 3.51, "very long token cannot consume the entire line");

        var shortFinal = TimedLyrics.parse("[00:10]one two six ten").wordCue(10000, 12000);
        check(shortFinal.endMs() == 12000, "known duration truncates final cue");
        for (int i = 0; i < 4; i++) {
            var word = shortFinal.words().get(i);
            check(word.startMs() == 10000 + i * 500 && word.endMs() == 10500 + i * 500,
                "short final line re-estimates all four words without end-time pileup");
        }
        var partialFinal = TimedLyrics.parse("[00:10]<00:10>one <00:10.5>two six ten").wordCue(10000, 12000);
        check(!partialFinal.words().getFirst().estimated() && partialFinal.words().getFirst().endMs() == 10500,
            "duration re-estimation preserves exact prefix boundary");
        check(partialFinal.words().get(1).startMs() == 10500 && partialFinal.words().get(1).endMs() == 11000
            && partialFinal.words().getLast().endMs() == 12000, "only inferred final group is redistributed");
        var unclosed = TimedLyrics.parse("[00:01]<00:01>One <00:02>orbit\n[00:04]Next").wordCue(1000, 10000);
        check(!unclosed.words().getFirst().estimated() && unclosed.words().getLast().estimated(), "missing last enhanced end is disclosed as inferred");

        var gaps = TimedLyrics.parse("[00:01]<00:01>One<00:01.5> <00:02>orbit<00:02.5>\n[00:03]\n[00:06]Again");
        var gapWords = gaps.wordCue(1750, 10000).words();
        check(gapWords.getFirst().endMs() == 1500 && gapWords.getLast().startMs() == 2000, "explicit within-line silent gap is preserved");
        close(gapWords.getFirst().progress(1750), 1, "completed word stays finished during gap");
        close(gapWords.getLast().progress(1750), 0, "next word waits during explicit gap");
        check(gaps.wordCue(3000, 10000).equals(TimedLyrics.WordCue.EMPTY), "blank line clears cue immediately");
        check(gaps.wordCue(5999, 10000).equals(TimedLyrics.WordCue.EMPTY), "blank instrumental gap stays empty");
        check(TimedLyrics.parse("[00:01]\u00A0\u2007\u202F").wordCue(1000, 5000).equals(TimedLyrics.WordCue.EMPTY),
            "Unicode space-only cue has no words to render");
        check(gaps.wordCue(6000, 10000).text().equals("Again"), "line resumes at precise end of blank gap");
        var longGap = TimedLyrics.parse("[00:01]Long wait\n[01:00]Resume");
        check(longGap.wordCue(16999, 90000).endMs() == 17000, "long intermediate cue has honest bounded end");
        check(longGap.wordCue(17000, 90000).equals(TimedLyrics.WordCue.EMPTY), "word cue expires at its advertised end");
        check(TimedLyrics.parse("[00:01]Last").wordCue(8999, 0).endMs() == 9000, "unknown-duration final line has bounded end");
        check(TimedLyrics.parse("[00:01]Last").wordCue(9000, 0).equals(TimedLyrics.WordCue.EMPTY), "unknown-duration final line expires");

        var repeated = TimedLyrics.parse("[00:01][00:06]<00:01>Same <00:02>words<00:03>");
        var first = repeated.wordCue(1000, 15000);
        var second = repeated.wordCue(6000, 15000);
        check(first.text().equals(second.text()) && first.id() != second.id(), "same lyric text still anchors each occurrence separately");
        for (int i = 0; i < first.words().size(); i++) {
            check(second.words().get(i).startMs() == first.words().get(i).startMs() + 5000
                && second.words().get(i).endMs() == first.words().get(i).endMs() + 5000, "multi-line stamps shift enhanced boundaries with each occurrence");
        }
        var duplicate = TimedLyrics.parse("[00:01]Old\n[00:01]<00:01>New<00:02>").wordCue(1000, 5000);
        check(duplicate.text().equals("New") && !duplicate.estimated(), "duplicate replacement includes enhanced metadata");
        var shifted = TimedLyrics.parse("[offset:+250]\n[00:01]<00:01>One <00:02>orbit<00:03>").wordCue(750, 10000);
        check(shifted.startMs() == 750 && shifted.words().getLast().endMs() == 2750, "line and enhanced anchors use the same offset");
        var beforeZero = TimedLyrics.parse("[offset:2000]\n[00:01]<00:01>One <00:03>orbit<00:04>").wordCue(0, 10000);
        check(beforeZero.words().getFirst().startMs() == 0 && beforeZero.words().getFirst().endMs() == 1000,
            "offset clamps both clocks consistently before zero");
        var descending = TimedLyrics.parse("[00:01]<00:02>One <00:01>orbit").wordCue(1000, 10000);
        check(descending.estimated() && descending.text().equals("One orbit"), "descending enhanced stamps fall back without leaking tags");
        check(TimedLyrics.parse("[00:01]<00:99>One").wordCue(1000, 10000).estimated(), "invalid enhanced seconds never presented as exact");
        var colon = TimedLyrics.parse("[00:01:25]<00:01:25>One<00:02:5>").wordCue(1250, 5000);
        check(colon.words().getFirst().endMs() == 2500 && !colon.estimated(), "colon enhanced fractions are supported");
        var spacedEnd = TimedLyrics.parse("[00:01]<00:01>One  <00:02>  ").wordCue(1000, 5000);
        check(spacedEnd.words().getFirst().endMs() == 2000 && !spacedEnd.estimated(), "trailing whitespace does not discard an explicit end stamp");

        var unicode = TimedLyrics.parse("[00:01]  <00:01>\uD83C\uDF19\u200D\u2728 <00:02>cafe\u0301\u2003<00:03>\u6C34\u9762<00:04>  ").wordCue(1000, 10000);
        check(unicode.words().size() == 3, "Unicode whitespace separates safe non-whitespace word groups");
        check(unicode.text().substring(unicode.words().getFirst().startChar(), unicode.words().getFirst().endChar()).equals("\uD83C\uDF19\u200D\u2728"),
            "emoji and joined cluster remains one word with UTF-16 offsets");
        check(!unicode.estimated(), "trimming and Unicode whitespace preserve enhanced alignment");
        var truncated = TimedLyrics.parse("[00:01]<00:01>" + "\uD83C\uDF19 ".repeat(240) + "<00:05>").wordCue(1000, 10000);
        check(truncated.text().codePointCount(0, truncated.text().length()) == TimedLyrics.MAX_TEXT, "word timeline retains original text limit");
        check(truncated.words().getLast().endChar() == truncated.text().length(), "truncated final word contains visible ellipsis, not hidden text");
        var overStamped = TimedLyrics.parse("[00:01]" + "<00:01>".repeat(400) + "bounded").wordCue(1000, 10000);
        check(overStamped.estimated() && overStamped.words().size() == 1, "enhanced anchor count is bounded with safe estimation fallback");

        var manual = new TimedLyrics(java.util.List.of(new TimedLyrics.Line(5000, "Later"), new TimedLyrics.Line(-1, "First")));
        check(manual.wordCue(0, 10000).text().equals("First") && manual.wordCue(5000, 10000).text().equals("Later"),
            "existing two-argument Line constructor normalizes safely");
        check(new TimedLyrics(null).wordCue(0, 0).equals(TimedLyrics.WordCue.EMPTY), "null manual timeline empty");
        var invalidManual = new TimedLyrics(java.util.List.of(new TimedLyrics.Line(1000, "Safe words", java.util.List.of(),
            java.util.List.of(new TimedLyrics.WordStamp(5, 3000), new TimedLyrics.WordStamp(0, 1000)))));
        check(invalidManual.wordCue(1000, 5000).estimated(), "malformed manually supplied enhanced anchors fall back safely");
        var hugeTime = new TimedLyrics(java.util.List.of(new TimedLyrics.Line(Long.MAX_VALUE - 5, "Last words")));
        check(hugeTime.wordCue(Long.MAX_VALUE - 4, 0).endMs() == Long.MAX_VALUE, "manual extreme timestamp saturates instead of overflowing");
        var oneMs = TimedLyrics.parse("[00:01]one two three\n[00:01.001]Next").wordCue(1000, 5000);
        check(oneMs.words().size() == 3, "sub-word-length interval remains bounded and defined");
        for (var timeline : java.util.List.of(exact, gaps, repeated, manual, hugeTime)) {
            for (var line : timeline.lines()) assertWords(timeline.wordCue(line.timeMs(), 0));
        }
        for (var sample : java.util.List.of(estimated, shortFinal, partialFinal, unclosed, shifted, beforeZero, descending, unicode, truncated, overStamped, oneMs)) assertWords(sample);
        java.util.Random random = new java.util.Random(0x51C0FFEE);
        for (int fixture = 0; fixture < 100; fixture++) {
            int words = 1 + random.nextInt(30);
            StringBuilder text = new StringBuilder("[00:01]");
            for (int i = 0; i < words; i++) text.append(i % 4 == 0 ? "\uD83C\uDF19" : "x".repeat(1 + random.nextInt(8))).append(' ');
            text.append("\n[00:10]Next");
            var timeline = TimedLyrics.parse(text.toString());
            var sample = timeline.wordCue(1000, 1001 + random.nextInt(8999));
            assertWords(sample);
            check(sample.equals(timeline.wordCue(1000, sample.endMs())), "estimated timing deterministic across repeated lookup");
        }
    }
    private static void assertWords(TimedLyrics.WordCue cue) {
        if (cue.text().isEmpty()) return;
        long previousTime = cue.startMs();
        int previousChar = 0;
        for (var word : cue.words()) {
            check(word.startChar() >= previousChar && word.endChar() > word.startChar() && word.endChar() <= cue.text().length(), "word character bounds ordered");
            check(word.startMs() >= previousTime && word.endMs() >= word.startMs() && word.endMs() <= cue.endMs(), "word time bounds ordered and inside cue");
            check(word.startChar() == 0 || !Character.isLowSurrogate(cue.text().charAt(word.startChar())), "word begins on Unicode boundary");
            check(!Character.isHighSurrogate(cue.text().charAt(word.endChar() - 1)), "word ends on Unicode boundary");
            double previousProgress = -1;
            for (int step = 0; step <= 10; step++) {
                long position = cue.startMs() + Math.round((cue.endMs() - cue.startMs()) * (step / 10.0));
                double progress = word.progress(position);
                check(Double.isFinite(progress) && progress >= previousProgress && progress >= 0 && progress <= 1, "finite monotonic per-word progress");
                previousProgress = progress;
            }
            previousTime = word.endMs();
            previousChar = word.endChar();
        }
    }
    private static void close(double actual, double expected, String message) { check(Math.abs(actual-expected) < 1.0e-9, message); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
