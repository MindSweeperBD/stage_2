package mindsweeper.indexing;

import mindsweeper.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizerTest {
    @Test
    void matchesStageOneLowercaseUnicodeDistinctWordRules() {
        Set<String> words = TextNormalizer.getWordsList("Árbol árbol A 42, hello-world!");
        assertEquals(Set.of("árbol", "42", "hello", "world"), words);
    }
}
