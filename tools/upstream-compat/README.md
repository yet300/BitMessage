# Pinned upstream compatibility reproduction

This harness recreates the Phase 1 `BitchatBaseline2026_08` producer and reciprocal-acceptance evidence plus the pinned Phase 4 packet-identity, signing, relay, and fragment known answers. It is deliberately excluded from ordinary PR CI: the checked-in neutral fixtures under `compatibility/` are the offline CI input.

Pinned sources:

- Apple: `permissionlesstech/bitchat@1f59e814f90c3f489f48d68262cb1bf640bf6181`
- Android: `permissionlesstech/bitchat-android@094657efa0aabbb6f71c9050149d1d01aee96400`

From the BitMessage repository root:

```bash
tools/upstream-compat/prepare-upstreams.sh /tmp/bitmessage-upstreams
tools/upstream-compat/install-harness.sh /tmp/bitmessage-upstreams
tools/upstream-compat/verify-upstreams.sh /tmp/bitmessage-upstreams
```

`prepare-upstreams.sh` refuses a checkout whose `HEAD` is not the pinned SHA. `install-harness.sh` copies only test sources into the disposable clones. `verify-upstreams.sh` executes five distinct evidence paths:

1. Apple upstream emits five deterministic outer-packet literals and two announcement literals.
2. Android upstream emits the same five semantic outer-packet cases and two announcement cases.
3. Apple decoders accept Android-emitted outer and announcement literals.
4. Android decoders accept Apple-emitted outer and announcement literals.
5. Both pinned clients execute the proposed Phase 4 packet-ID, fixed-TTL signing, signature-preserving relay, and 13-byte fragment-metadata assertions through their production helpers.

The first Phase 4 run exposed that both production signing helpers emit a 256-byte padded transcript, consisting of the 26-byte unpadded core followed by 230 `e6` bytes. The profile decision was subsequently amended to adopt those reproduced bytes. The harness asserts the exact core, total length, and padding rather than treating the 26-byte core alone as a signing transcript. Packet identity and fragment literals also match.

The extended announcement bytes intentionally differ in TLV order. The harness and neutral corpus preserve both raw encodings.

Prerequisites are Git, Xcode with the macOS SDK, JDK 17, and an Android SDK exposed through `ANDROID_HOME`. Network access is needed only while preparing the disposable pinned clones and resolving their own pinned dependencies. Do not point these scripts at a working upstream checkout: installation intentionally adds test files.

To compare emitted literals, inspect the `BITMESSAGE_FIXTURE` and `BITMESSAGE_PHASE4` lines in Swift/JUnit output and the Apple announcement file at `/tmp/bitmessage-phase1-apple-announcement-fixtures.txt`. Any fixture update must be reviewed together with the corresponding content-hash change and must not mix another upstream SHA into this profile.
