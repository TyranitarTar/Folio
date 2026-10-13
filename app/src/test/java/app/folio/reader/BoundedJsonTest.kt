package app.folio.reader

import org.junit.Assert.*
import org.junit.Test

class BoundedJsonTest {
    @Test fun depthAndValueBudgetsAreCheckedBeforeRecursiveParsing() {
        assertTrue(runCatching { BoundedJson.check("[".repeat(1000) + "0" + "]".repeat(1000)) }.isFailure)
        assertTrue(runCatching { BoundedJson.check("[0,0,0,0]", maxTokens = 3) }.isFailure)
    }
    @Test fun bracketsAndEscapedQuotesInsideTextDoNotConsumeNestingBudget() {
        BoundedJson.check("{\"text\":\"[[[\\\"[\"}", maxDepth = 1)
    }
}
