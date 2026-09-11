package com.acme.catalog.write;

import com.acme.catalog.config.IndexingProperties;
import com.acme.catalog.domain.Category;
import com.acme.catalog.domain.Vendor;
import com.acme.catalog.repo.CategoryRepository;
import com.acme.catalog.repo.VendorRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Changes to an entity that many documents embed — a category path, a vendor's country.
 *
 * <p>Both indexing pipelines need this, and they need different things from it:
 * <ul>
 *   <li><b>Hibernate Search</b> resolves the fan-out itself. Both associations are mapped
 *       {@code ReindexOnUpdate.DEFAULT} with an inverse collection on the parent, so one
 *       parent event is expanded by its event processor in the background. The library's
 *       own javadoc is explicit that the alternative, SHALLOW, obliges you to run
 *       "periodic batch processes ... to refresh the index of affected entities" — so
 *       SHALLOW without such a process is not a trade-off, it is a staleness bug.</li>
 *   <li><b>The hand-rolled pipeline</b> cannot: it is keyed by product id and has no idea
 *       vendors or categories exist. Its fan-out is written here, as one
 *       {@code INSERT … SELECT}.</li>
 * </ul>
 *
 * <p>Neither pipeline bounds the fan-out, so this does: the affected count is measured
 * first and the write refused past a configured limit, because a top-level category in a
 * real catalogue is millions of documents and that is not work for a web request.
 */
@Service
public class ParentAttributeWriteService {

    private static final Logger log = LoggerFactory.getLogger(ParentAttributeWriteService.class);

    private final CategoryRepository categories;
    private final VendorRepository vendors;
    private final NamedParameterJdbcTemplate jdbc;
    private final IndexingProperties properties;
    private final MeterRegistry meters;
    private final Map<String, DistributionSummary> fanOutByParent = new ConcurrentHashMap<>();

    public ParentAttributeWriteService(CategoryRepository categories, VendorRepository vendors,
                                       NamedParameterJdbcTemplate jdbc, IndexingProperties properties,
                                       MeterRegistry meters) {
        this.categories = categories;
        this.vendors = vendors;
        this.jdbc = jdbc;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * @param productsEnqueued        outbox rows written for the hand-rolled pipeline
     * @param hibernateSearchExpected documents Hibernate Search will reindex on its own
     */
    public record ParentChangeResult(String parent, long id, String change,
                                     int productsEnqueued, int hibernateSearchExpected) {
    }

    public static class FanOutTooLargeException extends RuntimeException {
        public FanOutTooLargeException(String parent, long id, int products, int limit) {
            super("Changing " + parent + " " + id + " would reindex " + products
                    + " products, over the limit of " + limit
                    + ". Make the change and run a mass reindex out of band instead.");
        }
    }

    @Transactional
    public ParentChangeResult renameCategory(long categoryId, String newPath) {
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new EntityNotFoundException("category " + categoryId));
        return apply("category", categoryId, "category_id",
                category.getPath() + " -> " + newPath, () -> {
                    category.rename(newPath);
                    return 0;
                });
    }

    @Transactional
    public ParentChangeResult reclassifyVendor(long vendorId, String country, String tier) {
        Vendor vendor = vendors.findById(vendorId)
                .orElseThrow(() -> new EntityNotFoundException("vendor " + vendorId));
        return apply("vendor", vendorId, "vendor_id",
                vendor.getCountry() + "/" + vendor.getTier() + " -> " + country + "/" + tier, () -> {
                    vendor.reclassify(country, tier);
                    return 0;
                });
    }

    private ParentChangeResult apply(String parent, long id, String foreignKey,
                                     String change, LongSupplier mutation) {
        int affected = affectedProducts(foreignKey, id);
        int limit = properties.maxParentFanOut();
        if (affected > limit) {
            throw new FanOutTooLargeException(parent, id, affected, limit);
        }

        mutation.getAsLong();

        int enqueued = jdbc.update(
                "INSERT INTO outbox_event (aggregate_type, aggregate_id) "
                        + "SELECT 'product', p.id FROM product p WHERE p." + foreignKey + " = :id",
                new MapSqlParameterSource("id", id));
        meters.counter("catalog.outbox.events.written", "pipeline", "hand-rolled").increment(enqueued);

        fanOutByParent.computeIfAbsent(parent, key -> DistributionSummary
                .builder("catalog.write.fanout")
                .description("Documents one parent change forces to be reindexed")
                .tag("trigger", key + "_change")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters)).record(affected);
        meters.counter("catalog.write.mutations", "operation", parent + "_change").increment();

        if (affected > 1000) {
            log.warn("Changing {} {} reindexes {} products; expect indexing lag to rise until "
                    + "both pipelines drain", parent, id, affected);
        }
        return new ParentChangeResult(parent, id, change, enqueued, affected);
    }

    private int affectedProducts(String foreignKey, long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE " + foreignKey + " = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        return count == null ? 0 : count;
    }
}
