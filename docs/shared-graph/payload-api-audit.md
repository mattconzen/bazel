# Initial value-family API audit and deferral decision

This source audit supplements [payload attribution](payload-audit.md) at binary
source `25c5664fcb31d915e7e2927864a722a1f80e44ed`. It records barriers before any
new payload implementation. It does not certify all caller contracts or select
an adapter from logical retained bytes alone.

## Configured targets

The larger native host profile attributes 54,479,936 joint retained modeled
bytes to `RuleConfiguredTargetValue`. Its source is a final concrete class. It
keeps an action list aliased from `RuleConfiguredTarget`; the constructor
deliberately avoids copying that list. A serialized copy plus an unchanged
private list would retain overlapping storage rather than remove the modeled
family.

Concrete-type consumers include `SkyframeExecutor`, `SequencedSkyframeExecutor`
statistics/cleanup and aquery's text, summary and proto callbacks. The latter
callbacks explicitly test/cast `RuleConfiguredTargetValue`. Merely implementing
`SkyValue` or `ConfiguredTargetValue` is insufficient to preserve those paths.

`AbstractConfiguredTargetValue` also has a mutable clearing contract:
`clear(false)` releases transitive packages, while `clear(true)` additionally
releases the configured target. The rule value retains actions separately.
A mapped body must preserve clearing, action lookup, ordinary queries, provider
and artifact identities, caller-retained objects and generation retirement. A
single decoder cache holding all values would defeat individual release.

The existing `RemoteConfiguredTargetValue.ConfiguredTargetValueCodec` is an
auditable possible building block, not a transparent local mapped adapter. Its
remote representation deliberately omits actions and transitive packages; the
remote rule's action access is unsupported. Reusing it without a separate local
execution/identity design would change the required behavior.

Sources:
[rule value](../../src/main/java/com/google/devtools/build/lib/skyframe/RuleConfiguredTargetValue.java),
[clear contract](../../src/main/java/com/google/devtools/build/lib/skyframe/AbstractConfiguredTargetValue.java),
[remote value/codec](../../src/main/java/com/google/devtools/build/lib/skyframe/RemoteConfiguredTargetValue.java),
[aquery consumer](../../src/main/java/com/google/devtools/build/lib/query2/aquery/ActionGraphProtoOutputFormatterCallback.java).

## Packages

`PackageValue` accounts for 32,159,808 joint retained modeled bytes in the larger
native profile. It holds a final `Package` reference and exposes that concrete
object through `getPackage()`. Ordinary package loading, target expansion,
configured-target construction and error handling cast graph values to
`PackageValue`; users then traverse package targets, rules, declarations and
metadata. Its existing AutoCodec explicitly permits `Package`.

Replacing only the outer value does not release the package object graph while
its concrete references remain. A viable internal body design needs measured
substructure ownership, alias/identity analysis, ordinary traversal and error
semantics, stable caller-held objects, and proof that clear/retirement releases
obsolete generations. Returning a newly decoded complete package on each access
is not an established memory optimization.

Sources:
[package value](../../src/main/java/com/google/devtools/build/lib/skyframe/PackageValue.java),
[package provider](../../src/main/java/com/google/devtools/build/lib/skyframe/GraphBackedRecursivePackageProvider.java),
[target expansion](../../src/main/java/com/google/devtools/build/lib/skyframe/TestsForTargetPatternFunction.java).

## Net estimate and decision

| Candidate | Measured ownership basis | Unmeasured replacement costs | Decision |
| --- | --- | --- | --- |
| Configured-target body | 54,479,936 logical joint retained modeled bytes in larger host dump | Encoded body, indexes, stable target/action/provider wrappers, decode caches, caller retention, clearing and publication buffers | Defer |
| Package body | 32,159,808 logical joint retained modeled bytes in larger host dump | Encoded targets/rules, concrete package identity, metadata/indexes, traversal caches, caller retention and generation lifetime | Defer |
| Existing mapped eager views/records | Estimated 14,990,000 removable shallow bytes per VM in the smaller diagnostic, before on-demand allocations | API reader identity, newly allocated views/garbage, retained readers and physical heap placement | Separate possible overhead cleanup; not implemented |

The rows overlap other roots or come from different fixtures and must not be
added. No measured conversion from these logical byte models to recoverable
private resident pages exists. Encoded/index/cache costs are unknown, so a
positive net resident estimate cannot yet be defended. These measurements also
do not impose an arena-size ceiling on wider JVM/cache/kernel opportunities.

**No new value family is selected.** Preserve canonical keys and Java identities,
retain the existing dependency representation and finish the independent
comparison of the already selected reporting/heap/backing policies, including
the identical policies on native. Improve attribution of the larger private
owners before expanding the format. Nested sets remain separately gated on
their wrapper/body identity and traversal design. This deferral closes the
selection decision for this iteration; it does not claim an implemented adapter,
physical saving or passing whole-host acceptance gate.
