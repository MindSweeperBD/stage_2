package com.mindsweeper.search;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

public class SearchEngine {

    public static final int DEFAULT_OFFSET = 0;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final String NON_ALPHANUMERIC_UNICODE = "[^\\p{L}\\p{N}]+";

    private final DatamartStore store;

    public record SearchQuery(
            String q,
            String author,
            String language,
            Integer year,
            int offset,
            int limit
    ) {}

    public record SearchResponse(
            String query,
            Map<String, Object> filters,
            int total,
            int offset,
            int limit,
            List<DatamartStore.BookRecord> results
    ) {}

    public SearchEngine(DatamartStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public SearchResponse search(SearchQuery request)
            throws IOException, SQLException {

        if (request.q() == null || request.q().isBlank()) {
            throw new IllegalArgumentException("Parameter 'q' is required and cannot be blank");
        }
        if (request.offset() < 0) {
            throw new IllegalArgumentException("Parameter 'offset' must be >= 0");
        }
        if (request.limit() <= 0 || request.limit() > MAX_LIMIT) {
            throw new IllegalArgumentException("Parameter 'limit' must be between 1 and " + MAX_LIMIT);
        }
        if (request.year() != null && request.year() <= 0) {
            throw new IllegalArgumentException("Parameter 'year' must be a positive integer");
        }

        List<String> terms = tokenize(request.q());
        Map<String, Object> activeFilters = buildActiveFiltersMap(request);

        if (terms.isEmpty()) {
            return new SearchResponse(
                    request.q().trim(),
                    activeFilters,
                    0,
                    request.offset(),
                    request.limit(),
                    List.of()
            );
        }

        Map<Integer, Integer> matchCounts = new HashMap<>();
        for (String term : terms) {
            Set<Integer> postings = store.getPostings(term);
            for (Integer bookId : postings) {
                matchCounts.merge(bookId, 1, Integer::sum);
            }
        }

        if (matchCounts.isEmpty()) {
            return new SearchResponse(
                    request.q().trim(),
                    activeFilters,
                    0,
                    request.offset(),
                    request.limit(),
                    List.of()
            );
        }

        Map<Integer, DatamartStore.BookRecord> booksById =
                store.getBooksByIds(matchCounts.keySet());

        List<DatamartStore.BookRecord> filtered = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : matchCounts.entrySet()) {
            DatamartStore.BookRecord book = booksById.get(entry.getKey());
            if (book != null && matchesFilters(book, request)) {
                filtered.add(book);
            }
        }

        filtered.sort((b1, b2) -> {
            int score1 = matchCounts.getOrDefault(b1.book_id(), 0);
            int score2 = matchCounts.getOrDefault(b2.book_id(), 0);
            if (score1 != score2) {
                return Integer.compare(score2, score1);
            }

            int titleHits1 = countTitleMatches(b1.title(), terms);
            int titleHits2 = countTitleMatches(b2.title(), terms);
            if (titleHits1 != titleHits2) {
                return Integer.compare(titleHits2, titleHits1);
            }

            return Integer.compare(b1.book_id(), b2.book_id());
        });

        int total = filtered.size();
        List<DatamartStore.BookRecord> page;
        if (request.offset() >= total) {
            page = List.of();
        } else {
            int end = Math.min(request.offset() + request.limit(), total);
            page = filtered.subList(request.offset(), end);
        }

        return new SearchResponse(
                request.q().trim(),
                activeFilters,
                total,
                request.offset(),
                request.limit(),
                page
        );
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = text
                .toLowerCase(Locale.ROOT)
                .replaceAll(NON_ALPHANUMERIC_UNICODE, " ")
                .trim();

        if (normalized.isEmpty()) {
            return List.of();
        }

        Set<String> uniqueTerms = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() > 1) {
                uniqueTerms.add(token);
            }
        }
        return List.copyOf(uniqueTerms);
    }

    private static boolean matchesFilters(
            DatamartStore.BookRecord book,
            SearchQuery query
    ) {
        if (query.author() != null && !query.author().isBlank()) {
            String expectedAuthor = query.author().trim().toLowerCase(Locale.ROOT);
            String bookAuthor = book.author() == null ? "" : book.author().toLowerCase(Locale.ROOT);
            if (!bookAuthor.contains(expectedAuthor)) {
                return false;
            }
        }

        if (query.language() != null && !query.language().isBlank()) {
            String expectedLang = DatamartStore.normalizeLanguageCode(query.language());
            String bookLang = DatamartStore.normalizeLanguageCode(book.language());
            if (!expectedLang.equalsIgnoreCase(bookLang)) {
                return false;
            }
        }

        if (query.year() != null) {
            if (book.year() == null || !query.year().equals(book.year())) {
                return false;
            }
        }

        return true;
    }

    private static int countTitleMatches(String title, List<String> terms) {
        if (title == null || title.isBlank()) {
            return 0;
        }
        List<String> titleTokens = tokenize(title);
        int hits = 0;
        for (String term : terms) {
            if (titleTokens.contains(term)) {
                hits++;
            }
        }
        return hits;
    }

    private static Map<String, Object> buildActiveFiltersMap(SearchQuery request) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (request.author() != null && !request.author().isBlank()) {
            filters.put("author", request.author().trim());
        }
        if (request.language() != null && !request.language().isBlank()) {
            filters.put("language", request.language().trim());
        }
        if (request.year() != null) {
            filters.put("year", request.year());
        }
        return filters;
    }
}
