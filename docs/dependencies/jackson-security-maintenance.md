# Jackson and Commons Lang maintenance — Part 6

Reviewed on 2026-10-29 following IDE/Mend warnings against Jackson 2.22.0 and
Commons Lang 3.17.0.

The shared Jackson BOM is updated to 2.22.4. Spring Boot modules explicitly use
that same BOM version through dependency management, keeping core, databind and
Java-time support aligned. Flink's shaded Jackson and libraries preinstalled in
container images are outside this version override.

Upstream release notes identify the supplied databind findings as fixed across
2.22.1–2.22.4, including CVE-2026-110879 in 2.22.4. The supplied core findings are
fixed in 2.22.2–2.22.3. This remains Jackson 2; no Jackson 3 migration is involved.

References:

- [Jackson databind release notes](https://github.com/FasterXML/jackson-databind/blob/2.22/release-notes/VERSION-2.x)
- [CVE-2026-110879 advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)
- [Jackson core release notes](https://github.com/FasterXML/jackson-core/blob/2.22/release-notes/VERSION-2.x)
- [Commons Lang release notes](https://commons.apache.org/proper/commons-lang/changes.html#release_3.18.0)

The Spring Boot modules select Commons Lang 3.18.0, the minimal fix for
CVE-2025-48924, also matching Flink's existing resolved version. The relevant
ClassUtils recursion fix is documented in the 3.18.0 release notes.

Validation includes all module tests, the db-centric executable JAR, and resolved
Gradle dependencies. This is targeted dependency maintenance, not a complete
vulnerability scan; refresh Mend separately. Historical benchmark results were
not regenerated, and container images were not deployed or scanned.
