package mindsweeper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetadataExtractor {
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("^Title:\\s(.+)$", Pattern.MULTILINE);
    private static final Pattern AUTHOR_PATTERN =
            Pattern.compile("^Author:\\s(.+)$", Pattern.MULTILINE);
    private static final Pattern LANGUAGE_PATTERN =
            Pattern.compile("^Language:\\s(.+)$", Pattern.MULTILINE);
    private static final Pattern YEAR_PATTERN =
            Pattern.compile("^Release date:\\s*.*?(\\d{4})", Pattern.MULTILINE);

    public BookMetadata extract(int bookId, String header, String bodyPath) {
        return new BookMetadata(
                bookId,
                extract(TITLE_PATTERN, header),
                extract(AUTHOR_PATTERN, header),
                extract(LANGUAGE_PATTERN, header),
                extractYear(header),
                bodyPath
        );
    }

    private static String extract(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static Integer extractYear(String content) {
        Matcher matcher = YEAR_PATTERN.matcher(content);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return null;
    }
}
