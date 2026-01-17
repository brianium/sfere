# Sfere Specifications

This document organizes the development priorities for Sfere - a Sandestin replacement for datastar.wow.deacon.

## Workflow Guidelines

**This metadocument and all specs are living documents.** They should be updated as work progresses to maintain an accurate picture of project state. This is critical for avoiding context loss during long sessions.

### Priorities
- Document current priorities clearly in the Work Queue below
- Update priority order as understanding evolves
- Mark specs as complete when finished

### Commits
- Make regular commits at logical points (completing a function, passing tests, finishing a spec)
- Don't batch too much work between commits
- Commit messages should reference the spec being implemented when relevant

### Spec Updates
- Update specs with implementation notes, discoveries, and changes as work progresses
- Add a "Status" or "Progress" section to track what's done vs remaining
- If implementation diverges from spec, update the spec to reflect reality
- Specs are the source of truth for resuming work after compaction

## Overview

Sfere provides SSE connection management for the Sandestin/Twk ecosystem, enabling:
- Connection storage and retrieval for out-of-band dispatch (REPL usage)
- Multiplayer/broadcast scenarios
- LLM-discoverable connection introspection

## Work Queue

### Current Priority
**[Core Connection Store](./001-connection-store.md)** — Start here. The registry depends on this.

### Active
1. [Core Connection Store](./001-connection-store.md) - Protocol and implementations (atom, caffeine)
2. [Sandestin Registry](./002-registry.md) - Effects, interceptors, and integration with Twk

### Completed
_None yet_

### Backlog
- Demo Application (lobby/room use case)
- GitHub CI Workflow
- Documentation and README

## Key Design Decisions

### Connection Key Structure
Keys follow a vector structure: `[:scope-id [:category :id]]`
- `scope-id` - Typically derived from session/user context
- `[:category :id]` - User-provided identifier (e.g., `[:room "lobby"]`, `[:user 42]`)

### Wildcard Matching
Use `:*` as a wildcard for broadcast matching:
- `[:* [:room "lobby"]]` - All users in the lobby room
- `[:user-123 :*]` - All connections for user-123
- `[:* :*]` - All connections (true broadcast)

## Dependencies

Sfere depends on (use git coordinates with tag/sha):
- `ascolais/sandestin` - Effect dispatch
- `ascolais/twk` - Datastar integration for Sandestin
- `com.github.benmanes.caffeine/caffeine` - For caffeine store implementation

## Resolved Questions

- [x] ~~Should broadcast support predicate-based filtering beyond key matching?~~ **No** — Key pattern matching with `:*` wildcards is sufficient
- [x] ~~Should there be connection metadata beyond the key for richer querying?~~ **No** — Keys are semantic (e.g., `[:user-123 [:room "lobby"]]`), no separate metadata needed
- [x] ~~What connection info should be exposed for LLM discoverability?~~ **`list-connections` with pattern matching, `connection`, `connection-count`**
