## Documentation & Schema Principles (binding at implementation time)

- **Schemas are the structural source of truth, not docstrings.**
  Response shapes (`TypeReference`, `:match`, `:id`, `:ns`, kind-explicit
  serialization forms) are expressed in the Prismatic schemas in
  `api.clj` with concise field-level docstrings.  Do NOT write large
  docstrings that enumerate data structures field-by-field — they go
  stale immediately and duplicate the schema.  A docstring states
  purpose and non-obvious semantics (e.g. "`known` distinguishes types
  linkable in this rulebase from hierarchy ghosts"); the schema states
  shape.
- **Docstrings describe the present, never the design process.**  No
  "previously", "used to", "now", "revised", or comparisons to
  replaced behavior.  Write what the code does and why, as if it had
  always worked that way.  (Rationale history lives in this plan and in
  git, not in code.)
- **Code never references this plan.**  This document is ephemeral
  design-phase material; no docstring or comment may cite it, its
  section numbers, or its bullet points.  `docs/explorer-graph-api.md`
  and `server/docs/internal-analysis-models.md` are maintained project
  docs and MAY be referenced — sparingly, and only from other docs or
  from code whose behavior the doc genuinely tracks.
- **Linking direction: docs → code, not code → docs.**  Implementation
  details are owned by the executable code (and its schemas); project
  docs cite namespaces/functions when they need precision.  A docstring
  should not point at a doc for its own contract — the contract is the
  code.  When updating `explorer-graph-api.md`, link/cite the
  implementing vars (e.g. `serialize/resolve-type`, `serialize/route-id`)
  rather than restating their logic.
- **Project docs are updated, not appended-as-history.**
  `explorer-graph-api.md` and `internal-analysis-models.md` describe
  the new state directly (serialization table, id scheme, `:ancestors`,
  `:match`), not a changelog of this refactor.

---

