package com.acme.catalog.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a catalogue big enough for the difference between the two engines to be
 * visible rather than anecdotal. Plain JDBC batches, explicit ids, fixed seed --
 * a JPA-based seeder for this volume would take minutes and prove nothing.
 */
@Component
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int BATCH = 1000;

    private static final String[] COUNTRIES = {"DE", "FR", "IT", "ES", "NL", "PL", "GB", "US"};
    private static final String[] TIERS = {"BRONZE", "SILVER", "GOLD", "PLATINUM"};
    private static final String[] ROOTS = {"apparel", "footwear", "home", "outdoor", "electronics"};
    private static final String[] MID = {"shirts", "jackets", "trousers", "accessories"};
    private static final String[] LEAF = {"oxford", "casual", "premium"};
    private static final String[] MATERIALS = {"cotton", "wool", "linen", "polyester", "leather"};
    private static final String[] FITS = {"slim", "regular", "loose"};
    private static final String[] ADJECTIVES = {"Classic", "Modern", "Rugged", "Light", "Heavy", "Everyday", "Premium"};
    private static final String[] NOUNS = {"Shirt", "Jacket", "Trousers", "Boots", "Lamp", "Backpack", "Headset"};
    private static final String[] TAGS = {"sale", "new", "bestseller", "eco", "limited", "clearance"};
    private static final String[] REGIONS = {"EU", "US", "APAC"};
    private static final String[] STATUSES = {"ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE", "ACTIVE",
            "ACTIVE", "ACTIVE", "ACTIVE", "DRAFT", "ARCHIVED"};

    private final JdbcTemplate jdbc;

    public DataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long productCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Intentionally not @Transactional: a single transaction holding hundreds of
     * thousands of inserts is a demo of InnoDB undo-log growth, not of search.
     */
    public void seed(int productCount, boolean enqueue) {
        long startedAt = System.currentTimeMillis();
        Random random = new Random(42);

        List<Object[]> vendors = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            // Independent draws: deriving both from i makes country and tier
            // perfectly correlated, and half the benchmark scenarios then match
            // nothing -- a seeding artefact that looks like a query bug.
            vendors.add(new Object[]{i, "Vendor " + i, COUNTRIES[random.nextInt(COUNTRIES.length)],
                    TIERS[random.nextInt(TIERS.length)], BigDecimal.valueOf(2 + random.nextDouble() * 3)
                    .setScale(2, RoundingMode.HALF_UP)});
        }
        jdbc.batchUpdate("INSERT INTO vendor (id, name, country, tier, rating) VALUES (?, ?, ?, ?, ?)", vendors);

        List<Object[]> categories = new ArrayList<>();
        List<String> leafPaths = new ArrayList<>();
        List<Long> leafIds = new ArrayList<>();
        long categoryId = 0;
        for (String root : ROOTS) {
            long rootId = ++categoryId;
            categories.add(new Object[]{rootId, null, root, "/" + root});
            for (String mid : MID) {
                long midId = ++categoryId;
                categories.add(new Object[]{midId, rootId, mid, "/" + root + "/" + mid});
                for (String leaf : LEAF) {
                    long leafId = ++categoryId;
                    String path = "/" + root + "/" + mid + "/" + leaf;
                    categories.add(new Object[]{leafId, midId, leaf, path});
                    leafPaths.add(path);
                    leafIds.add(leafId);
                }
            }
        }
        jdbc.batchUpdate("INSERT INTO category (id, parent_id, name, path) VALUES (?, ?, ?, ?)", categories);

        List<Object[]> products = new ArrayList<>(BATCH);
        List<Object[]> variants = new ArrayList<>(BATCH);
        List<Object[]> inventory = new ArrayList<>(BATCH);
        List<Object[]> attributes = new ArrayList<>(BATCH);
        List<Object[]> tags = new ArrayList<>(BATCH);
        long variantId = 0;
        long inventoryId = 0;
        long attributeId = 0;

        for (int id = 1; id <= productCount; id++) {
            String material = MATERIALS[random.nextInt(MATERIALS.length)];
            String fit = FITS[random.nextInt(FITS.length)];
            String noun = NOUNS[random.nextInt(NOUNS.length)];
            String name = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " " + material + " " + noun;
            int leafIndex = random.nextInt(leafIds.size());
            products.add(new Object[]{
                    id, "SKU-" + id, name,
                    name + " made from " + material + ", " + fit + " fit. Durable everyday " + noun.toLowerCase()
                            + " with a reinforced finish and a two year warranty.",
                    "Brand" + (id % 40), STATUSES[random.nextInt(STATUSES.length)],
                    1 + random.nextInt(60), leafIds.get(leafIndex), "EUR",
                    BigDecimal.valueOf(1 + random.nextDouble() * 4).setScale(2, RoundingMode.HALF_UP),
                    random.nextInt(2000),
                    Date.valueOf(LocalDate.of(2020, 1, 1).plusDays(random.nextInt(2000)))});

            int variantCount = 1 + random.nextInt(4);
            for (int v = 0; v < variantCount; v++) {
                long currentVariant = ++variantId;
                variants.add(new Object[]{currentVariant, id, "SKU-" + id + "-" + v,
                        new String[]{"black", "white", "navy", "olive"}[v % 4],
                        new String[]{"S", "M", "L", "XL"}[v % 4],
                        200 + random.nextInt(1800),
                        BigDecimal.valueOf(5 + random.nextDouble() * 195).setScale(2, RoundingMode.HALF_UP)});
                int stockRows = 1 + random.nextInt(2);
                for (int s = 0; s < stockRows; s++) {
                    String region = REGIONS[random.nextInt(REGIONS.length)];
                    inventory.add(new Object[]{++inventoryId, currentVariant, region + "-WH" + s, region,
                            random.nextInt(50)});
                }
            }

            attributes.add(new Object[]{++attributeId, id, "material", material});
            attributes.add(new Object[]{++attributeId, id, "fit", fit});
            attributes.add(new Object[]{++attributeId, id, "origin", COUNTRIES[random.nextInt(COUNTRIES.length)]});
            attributes.add(new Object[]{++attributeId, id, "warranty", (1 + random.nextInt(3)) + "y"});

            int tagCount = 1 + random.nextInt(3);
            for (int t = 0; t < tagCount; t++) {
                tags.add(new Object[]{id, TAGS[(id + t) % TAGS.length]});
            }

            if (products.size() >= BATCH) {
                flush(products, variants, inventory, attributes, tags);
            }
        }
        flush(products, variants, inventory, attributes, tags);

        if (enqueue) {
            jdbc.update("""
                    INSERT INTO outbox_event (aggregate_type, aggregate_id)
                    SELECT 'product', id FROM product
                    """);
        }
        log.info("Seeded {} products ({} variants, {} inventory rows) in {} ms",
                productCount, variantId, inventoryId, System.currentTimeMillis() - startedAt);
    }

    private void flush(List<Object[]> products, List<Object[]> variants, List<Object[]> inventory,
                       List<Object[]> attributes, List<Object[]> tags) {
        if (!products.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO product (id, sku, name, description, brand, status, vendor_id, category_id,
                                         currency, rating_avg, rating_count, launch_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, products);
            products.clear();
        }
        if (!variants.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO product_variant (id, product_id, sku, color, size, weight_grams, price)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, variants);
            variants.clear();
        }
        if (!inventory.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO inventory (id, variant_id, warehouse_code, region, quantity)
                    VALUES (?, ?, ?, ?, ?)
                    """, inventory);
            inventory.clear();
        }
        if (!attributes.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO product_attribute (id, product_id, attr_key, attr_value) VALUES (?, ?, ?, ?)
                    """, attributes);
            attributes.clear();
        }
        if (!tags.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO product_tag (product_id, tag) VALUES (?, ?)", tags);
            tags.clear();
        }
    }
}
