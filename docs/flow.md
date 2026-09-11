# Flow: which class does what

The same map as [`catalog-flow.html`](catalog-flow.html), in a form that renders in a
pull request. Published version:
https://claude.ai/code/artifact/cb690657-af90-4923-a1a9-ec939d7a0f02

```mermaid
flowchart TB
    subgraph W["WRITE — one transaction, three stores"]
        direction LR
        WC["ProductWriteController<br/><i>api</i>"] --> WS["ProductWriteService<br/><i>write · @Transactional</i>"]
        WS -->|"JPA repos + entities"| TBL[("product · product_variant<br/>inventory · attributes · tags")]
        WS -->|"OutboxRecorder · 1 row per aggregate"| OB[("outbox_event")]
        WS -->|"library listener · 1 row per touched entity"| HOB[("hsearch_outbox_event")]
    end

    subgraph IDX["INDEXING — two pipelines, no shared code"]
        direction LR
        OB -->|"poll 200ms · SKIP LOCKED"| RELAY["OutboxRelay<br/><i>outbox</i>"]
        RELAY --> ASM["ProductProjectionAssembler<br/>→ ProductDocument"]
        ASM --> IDXR["ProductIndexer<br/><i>bulk · external_gte</i>"]
        IDXR --> OSI[("products_read")]

        HOB -->|"outbox-polling"| HSP["Hibernate Search<br/>event processor"]
        HSP --> DERIV["Product derived getters<br/><i>@IndexingDependency</i>"]
        DERIV --> HSI[("hs-products-read")]
    end

    subgraph R["READ — pick an engine, get ids, hydrate"]
        direction LR
        QC["ProductQueryController<br/><i>api</i>"] -->|ProductQuery| RS["ProductReadService<br/><i>read</i>"]
        RS --> RT["ProductSearchRouter<br/><i>engine switch · shadow · fallback</i>"]
        RT --> A1["MySqlProductSearchAdapter"]
        RT --> A2["OpenSearchProductSearchAdapter<br/><i>+ ProductQueryTranslator</i>"]
        RT --> A3["HibernateSearchProductAdapter"]
    end

    A1 -.->|joins| TBL
    A2 -.-> OSI
    A3 -.-> HSI
    A1 & A2 & A3 -->|"SearchSlice · ids + total"| LOAD["ProductAggregateLoader<br/><i>read · on BOTH paths</i>"]
    RELAY -.->|"same loader builds the projection"| LOAD
    LOAD -->|"hydrate by PK · 5 batched queries"| TBL
    LOAD --> PR["PageResponse<br/><i>items · engine · timings</i>"] --> QC

    classDef write fill:#f7efe4,stroke:#9a5b13,color:#171c19
    classDef read fill:#e5f0f3,stroke:#0b6a80,color:#171c19
    classDef both fill:#eeebf5,stroke:#5a4b8a,color:#171c19
    class WC,WS write
    class QC,RS,RT,A1,A2,A3,PR read
    class LOAD both
```

**No arrow runs from the write path to an index.** That absence is the design: a search
outage cannot fail a POST, because the write never waited on one.

`ProductAggregateLoader` appears once and is called twice — by the relay to build the
projection, and by the read path to hydrate the page. One definition of an aggregate's
shape, so a document cannot quietly disagree with a response.
