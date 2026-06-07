package com.eight87.whisperboy.data.library

/**
 * Library-screen UI preferences — render mode, sort key, filter chip.
 *
 * These three enums live in `data/library/` (not `ui/library/`) so the [LibraryUiSettings]
 * facet in this package can reference them without inverting the dependency direction
 * (data must not depend on UI). The pure-Kotlin sort/filter functions that consume them
 * still live alongside the composables in `ui/library/LibrarySorting.kt` — they're
 * pure-Kotlin and either layer works, but co-locating them with the screen keeps the
 * UI code path readable.
 */

/** Grid vs list rendering for the library screen. */
enum class GridMode { Grid, List }

/**
 * Sort keys for the library grid / list.
 *
 * - [Recent] — `lastPlayedAt` descending, never-played books last.
 * - [Title] — collation-aware title ascending.
 * - [Author] — collation-aware author ascending, unknown-author bucket last.
 * - [Progress] — fraction-finished descending (closest-to-done first), un-started books last.
 */
enum class BookSortKey { Recent, Title, Author, Progress }

/**
 * Filter chips above the library grid.
 *
 * - [All] — every active book
 * - [Current] — started (`lastPlayedAt != null`) and not yet completed
 * - [NotStarted] — never played AND not marked completed
 * - [Completed] — explicitly marked completed via the long-press action sheet (Phase E.5)
 */
enum class BookFilter { All, Current, NotStarted, Completed }
