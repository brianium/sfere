# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.5.0] - 2025-01-19

### Added
- `purge-by-connection!` in registry interceptor: purges connections by matching SSE object on close, even without key in context

### Changed
- Demo simplified to "explicit leave only" — "user left" broadcasts only on Leave button click
- TTL expiry now silently cleans up connections (no automatic departure broadcast)

### Documentation
- Clarified sfere's scope: connection storage library, not presence monitoring
- Added spec 011 documenting presence detection patterns for applications needing real-time notifications

## [0.4.0] - 2025-01-19

### Added
- `:on-evict` callback option for atom store using `add-watch`
- `:on-evict` callback option for Caffeine store using `RemovalListener`
- `:ticker` and `:executor` options for Caffeine store (testing support)
- `clean-up!` function for Caffeine store to trigger maintenance

### Changed
- Unified callback model: both stores now use `:on-evict` with signature `(fn [key conn cause])`
- Demo uses Caffeine store with TTL-based user departure detection

### Removed
- `:on-purge` option from registry (use store-level `:on-evict` instead)

## [0.3.0] - 2025-01-18

### Added
- `:expiry-mode` option for Caffeine store (`:sliding` or `:fixed`)
- GitHub CI workflow with Java 17/21 test matrix

### Removed
- `tap>` calls from public APIs

## [0.2.0] - 2025-01-17

### Fixed
- Documentation clarified that SSE persistence is controlled by `::twk/with-open-sse?`, not HTTP method

## [0.1.0] - 2025-01-16

### Added
- Core connection store protocol with atom and Caffeine implementations
- Sandestin registry with `::sfere/broadcast` and `::sfere/with-connection` effects
- Automatic connection storage on SSE establishment
- Automatic connection purge on SSE close
- Wildcard pattern matching for broadcasts (`:*`)
- Demo lobby application showcasing multiplayer chat
