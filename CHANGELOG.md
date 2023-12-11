# Changelog

## [Unreleased][HEAD]

* Exhaustive API documentation
* `down` transitions (e.g., `stop!`, `pause!`) no longer include dependencies
  for execution
* Inject `:app-log` on all transitions (including `down` and `tx`)
* Plugins can extend the module with additional keys

## [v0.8.0-alpha][0.8.0-alpha]
2023-12-11

Initial codebase extracted from the [Pitch](https://github.com/pitch-io)
private monorepo.

[HEAD]: https://github.com/codebeige/moira/compare/v0.8.0-alpha...HEAD
[0.8.0-alpha]: https://github.com/codebeige/moira/releases/tag/v0.8.0-alpha
