package com.acme.catalog;

import com.acme.catalog.query.ProductQuery;
import com.acme.catalog.query.SortDirection;
import com.acme.catalog.query.SortField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a bug that only shows up past page zero: widening the search
 * window to refill a page must not move where the page starts. Deriving the offset
 * from the widened size silently skips rows -- page 1 of size 20 would begin at row
 * 25 instead of 20, and rows 20-24 would never be returned to anyone.
 */
class ProductQueryWindowTest {

    private ProductQuery page(int page, int size) {
        return new ProductQuery(null, List.of("ACTIVE"), null, null, null, List.of(), List.of(), Map.of(),
                null, null, null, null, SortField.RATING, SortDirection.DESC, page, size, null);
    }

    @Test
    void offsetIsPageTimesSizeByDefault() {
        assertThat(page(0, 20).offset()).isZero();
        assertThat(page(1, 20).offset()).isEqualTo(20);
        assertThat(page(100, 1000).offset()).isEqualTo(100_000);
    }

    @Test
    void wideningTheWindowKeepsThePageStart() {
        ProductQuery widened = page(1, 20).withWindow(25);

        assertThat(widened.size()).isEqualTo(25);
        assertThat(widened.offset()).isEqualTo(20);
    }

    @Test
    void repeatedWideningStillKeepsThePageStart() {
        ProductQuery widened = page(100, 1000).withWindow(1005).withWindow(2000).withWindow(4000);

        assertThat(widened.size()).isEqualTo(4000);
        assertThat(widened.offset()).isEqualTo(100_000);
    }
}
