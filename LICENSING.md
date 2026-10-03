# Licensing

## Original Delta Vault material

To the extent that they hold copyright and related rights in the original Delta Vault material,
its rights holders dedicate that material to the public domain under
[CC0 1.0 Universal](LICENSE). The full, unmodified CC0 legal code is in `LICENSE`.

This dedication covers the independently created material in this repository, including:

- The `app/` and `core/` implementations, tests, AIDL interfaces, manifests and resources.
- Build configuration, dependency lockfiles and repository configuration.
- Documentation and test-fixture generator scripts.
- Generated video, image and PDF test fixtures under `app/src/androidTest/assets/`.

## Third-party exclusions

The CC0 dedication does not apply to third-party material, even when it is checked into this
repository or packaged with the application. In particular:

- `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are Gradle Wrapper code under
  Apache-2.0. Their original copyright and license notices remain applicable.
- Upstream legal texts and copyright inventories under `app/src/main/assets/licenses/` retain
  their respective terms; retaining a license document does not relicense the upstream component.
- Dependencies such as Cryptomator cryptolib, LibVLC and libavif retain their original licenses.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the dependency inventory and binary
distribution requirements. Third-party copyright notices must be preserved where their terms require it.

## Combined application distribution

The current application links against Cryptomator cryptolib 2.2.0 under AGPL-3.0. Distribution of
the combined application requires compliance with AGPL-3.0, including corresponding-source
obligations, as well as the other dependencies' terms. The retained AGPL text is in
[app/src/main/assets/licenses/AGPL-3.0.txt](app/src/main/assets/licenses/AGPL-3.0.txt).

The original Delta Vault material may still be reused separately under CC0. This dedication does
not remove the licenses or obligations attached to any third-party component.
