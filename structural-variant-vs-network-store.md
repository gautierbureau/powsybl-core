# Structural-variant spike vs. powsybl-network-store "partial variants"

`powsybl-network-store` (the DB-backed IIDM implementation) already solved the same problem in
production, with its **partial variant** feature. This note maps their design onto this spike, after
reading `network-store-server`'s `PartialVariantUtils` and `NetworkStoreRepository`. The headline:
**two independent implementations converged on the identical delta-resolution algorithm.**

## The model

- network-store: every network variant carries a `fullVariantNum` (`NetworkAttributes`). `-1` means a
  **FULL** variant (self-contained); otherwise the variant is **PARTIAL** and `fullVariantNum` points
  at the FULL variant it derives from. A partial variant stores only its diff (added/updated resources
  + tombstones); reads fall through to the full variant.
- this spike: a structural **branch** is an `OverlayNetworkIndex` over a base `NetworkIndex` — the same
  added/tombstoned/fall-through, in memory, layered on IIDM's existing state-variant mechanism.

## The algorithms are the same

| operation | network-store (`PartialVariantUtils`) | this spike (`OverlayNetworkIndex`) |
|---|---|---|
| get one | `getOptionalIdentifiable`: partial → (tombstoned? empty) → full | `get`: added → (tombstoned? null) → base |
| get all | `getIdentifiables`: full **minus** (partialIds ∪ tombstonedIds) **plus** partial | `getAll`: base minus `shadowed` (added∪tombstoned) plus added |
| create branch | clone **from FULL**: set `fullVariantNum = source`, copy **no** elements (O(1)) | `createStructuralBranch`: overlay with empty delta (O(1)) |
| "flatten" | read-time merge over every table (full+partial−tombstoned) — done lazily per read | `BranchFlattener`: materialise the merged view once, for serialization |

`getOptionalIdentifiable` (partial-first, then tombstone, then full) is line-for-line our `get`
(added-first, then tombstone, then base). `getIdentifiables` excludes both partial ids and tombstoned
ids from the full list before adding the partial — exactly our `shadowed()` = `added ∪ tombstoned`. We
arrived at this independently; their production code validates it.

## What they do that we should learn from

1. **Tombstones at every granularity, not just per-object.** network-store has separate tombstone sets
   for identifiables, **external attributes (extensions)**, operational-limits groups, and regulating
   points — and runs the same full+partial−tombstone merge for each table. This is precisely our
   remaining "extensions / other attributes in materialisation" item: generalising the overlay to
   extensions/limits needs a tombstone + merge **per kind of attached data**, not one per object.
2. **Derived/filtered views need care.** Their `getRegulatingEquipments` has a documented subtlety
   (a `WHERE regulatedequipmenttype = …` view breaks naive full-override-by-partial, needing an extra
   "exclude updated-in-partial" fetch). The analogue here is our bus-view folding, which likewise had
   to *exclude shadowed* base terminals before adding branch-attached ones — same class of bug.
3. **State and structure are unified from the start** (everything is a `variantNum`-tagged resource),
   so there is no separate "structural overlay vs state variant" split — their model is natively what
   our "unified operating point" remaining item is reaching for.

## The essential architectural difference (why we can't just copy them)

- network-store resolves the delta **lazily at every read** — a DB query merges full+partial−tombstoned.
  Variants share nothing by reference; each variant's resources are separate rows. Partial variants
  save **storage**, not object graph.
- this spike shares the **in-memory object graph by reference** (the whole point: avoid the ~950 ms /
  88 MB full `Network.copy` for a fault-on-line split) and only materialises on serialization. Its
  extra complexity — branch-scoped terminal attachment, bus-view folds — exists **because** objects are
  shared in memory across base and branch; network-store never has that problem because it never shares
  objects, it re-resolves rows.

So their **partial-variant logic** (delta + fall-through + per-table tombstones) maps directly onto
ours and is the reference to follow when generalising; their **motivation** (DB storage) differs from
ours (in-memory copy avoidance), which is exactly why the in-memory version needs the reference-sharing
machinery they don't.
