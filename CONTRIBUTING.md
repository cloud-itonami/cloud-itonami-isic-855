# Contributing to cloud-itonami-isic-855

Thank you for considering contributing to the cloud-itonami-isic-855 repository!
This document outlines the process for contributing to this ISIC-855 (Educational support activities) actor.

## How to Contribute

1. **Fork the repository** (if you don't have write access).
2. **Create a new branch** for your feature or fix: `git checkout -b feature/your-feature-name`.
3. **Make your changes** following the coding standards and guidelines below.
4. **Run the test suite** to ensure nothing is broken: `kbb scripts/prepare-tests.cljk`, then `kbb --classpath "scripts:.test-tree:<pinned-langchain>/src:<pinned-text>/src" .test-tree/runner.cljk` (see the README under "Testing").
5. **Commit your changes** with a clear and descriptive message.
6. **Push to your branch** and open a pull request against the `main` branch of this repository.

## Coding Standards

- Source is Kotoba (`.kotoba` files under `src/testadmn/`).
- Follow the existing style in the repository (indentation, naming, etc.).
- All new functions should have appropriate docstrings.
- Keep the governor's HARD checks in mind: any change must not violate the HARD checks listed in the README.

## Testing

- Tests are written in `.kotoba` files (Kotoba SCI) and reside in the `test` directory.
- To run the full test suite, use the command provided in the README under "Testing".
- When adding new features, add corresponding tests to ensure coverage.

## Governor Compliance

This repository implements the HARD checks listed in the README; they are permanent and un-overridable.
Any contribution must ensure that:
- New operations or changes to existing operations do not bypass any HARD check.
- The governor's :propose-only allowlist is respected.
- The staged phases (0-3) are maintained.

## Reporting Issues

Please use the GitHub issue tracker to report bugs or suggest features.
Include as much detail as possible, including steps to reproduce for bugs.

## License

By contributing, you agree that your contributions will be licensed under the AGPL-3.0-or-later license of this project.
