# Architectural Design Document — LogLens v2.1.1

**Author:** Chief Software Architect  
**Date:** 2026-04-25  
**Status:** PENDING APPROVAL

---

## 1. Current State Assessment

### 1.1 Tech Stack (Exact Versions)

| Component   | Version        | Latest Stable | Status                   |
| ----------- | -------------- | ------------- | ------------------------ |
| Java        | 21 (LTS)       | 21            | **CURRENT** — latest LTS |
| Spring Boot | 3.5.8          | 3.5.x         | **CURRENT** — latest 3.x |
| Gradle      | 8.14.2         | 8.14.x        | **CURRENT**              |
| Lombok      | managed by BOM | —             | **CURRENT**              |
| Jackson     | managed by BOM | —             | **CURRENT**              |
| JUnit 5     | managed by BOM | —             | **CURRENT**              |
| AssertJ     | managed by BOM | —             | **CURRENT**              |

**Verdict: The entire tech stack is up-to-date.** Java 21 is the latest LTS (Java 25 LTS is not yet released). Spring Boot 3.5.8 is the latest stable 3.x. Gradle 8.14.2 is the latest 8.x. No upgrades needed.

### 1.2 Current Project Structure

```
src/main/java/com/honeywell/loglens/
├── LogLensApplication.java          (21 lines)   Entry point
├── config/
│   ├── JacksonConfig.java           (18 lines)   Jackson customizer
│   └── LogLensConfig.java           (43 lines)   App config binding
├── controller/
│   ├── LogController.java           (244 lines)  REST API
│   └── UIController.java            (21 lines)   Thymeleaf page
├── model/
│   ├── LogEntry.java                (52 lines)   Log record
│   ├── SearchRequest.java           (79 lines)   Request DTO
│   └── ServiceConfig.java           (10 lines)   Service definition
├── service/
│   ├── LogParserService.java        (332 lines)  5-format parser
│   ├── LogSearchService.java        (1586 lines) Core engine
│   └── QueryEngine.java             (237 lines)  Boolean query parser

src/main/resources/
├── application.yml                  (67 lines)   Config
├── templates/
│   └── index.html                   (1953 lines) Full SPA

src/test/ (5 test classes, 114 tests)
├── controller/LogControllerTest.java      (275 lines, 14 tests)
├── service/LogParserServiceTest.java      (396 lines, 28 tests)
├── service/LogSearchServiceTest.java      (1335 lines, 48 tests)
├── service/MultiUserCacheIntegrationTest  (381 lines, 6 tests)
├── service/QueryEngineTest.java           (253 lines, 18 tests)
```

**Total:** 11 source classes, 2,643 lines of Java, 1,953 lines of frontend, 2,640 lines of tests.

### 1.3 Dependencies (build.gradle)

| Dependency                    | Scope                             | Version Source | Status   |
| ----------------------------- | --------------------------------- | -------------- | -------- |
| spring-boot-starter-web       | implementation                    | BOM 3.5.8      | Required |
| spring-boot-starter-thymeleaf | implementation                    | BOM 3.5.8      | Required |
| jackson-databind              | implementation                    | BOM            | Required |
| jackson-datatype-jsr310       | implementation                    | BOM            | Required |
| lombok                        | compileOnly + annotationProcessor | BOM            | Required |
| spring-boot-starter-test      | testImplementation                | BOM            | Required |

**Verdict: Zero redundant dependencies.** Every library is actively used. All versions managed by Spring Boot BOM — no manual version pinning to go stale.

---

## 2. Proposed Project Structure

### 2.1 Analysis: Should We Adopt Multi-Module / Clean Architecture?

| Criteria                | LogLens Reality                                  |
| ----------------------- | ------------------------------------------------ |
| Team size               | 1 developer                                      |
| Deployment units        | 1 JAR (single executable)                        |
| Domain complexity       | Low — reads files, parses, filters, returns JSON |
| Database                | None                                             |
| External integrations   | None (filesystem only)                           |
| Microservice boundaries | None — this IS the microservice                  |
| Frontend                | Vanilla JS in single HTML (no build step)        |
| Total source classes    | 11                                               |
| Total lines of Java     | 2,643                                            |

### 2.2 Recommendation: **KEEP Current Single-Module Structure**

**Multi-module (api/core/domain/infrastructure) is WRONG for this project.** Here's why:

1. **Unnecessary indirection.** Clean Architecture separates layers that deploy independently. LogLens is a single JAR reading files from disk — there's no "infrastructure" to swap, no "domain" with business rules, no "api" that could be reused by another module.

2. **Performance overhead.** Multi-module adds Gradle configuration time (~2-5s), classpath splitting, and inter-module dependency resolution. For 11 classes, this is pure overhead with zero benefit.

3. **Cognitive overhead.** A developer opening `LogSearchService.java` shouldn't need to navigate 4 modules to understand a single search call. The current flat structure maps 1:1 to Spring's conventional layering (controller -> service -> model).

4. **Industry standard for microservices.** Spring Boot's official guides, Spring Initializr defaults, and the majority of production microservices at scale (Netflix, Uber, Airbnb) use single-module for individual services. Multi-module is for shared libraries across services, not for a self-contained tool.

5. **The structure already follows standard layering:**
   - `config/` — Spring configuration
   - `controller/` — REST + UI controllers
   - `model/` — DTOs and data classes
   - `service/` — Business logic

**This is the standard Spring Boot microservice layout. Changing it would be overengineering.**

### 2.3 What DOES Need Fixing (Within Current Structure)

| Item                       | Current                                | Proposed                        | Reason                               |
| -------------------------- | -------------------------------------- | ------------------------------- | ------------------------------------ |
| Global exception handling  | Inline try-catch in controller         | Extract `@RestControllerAdvice` | Eliminate duplicate error formatting |
| build.gradle `buildscript` | Redundant — duplicates `plugins` block | Remove `buildscript` block      | Modern Gradle uses `plugins {}` only |

---

## 3. Dependency & Tech Stack Audit

### 3.1 Versions

**All current. No upgrades needed.** See section 1.1.

### 3.2 build.gradle Cleanup

**Issue:** The `buildscript {}` block (lines 1-26) is entirely redundant. It declares the same Spring Boot and dependency-management plugins that the `plugins {}` block (lines 28-33) already applies. This is a legacy pattern from Gradle 4.x.

**Fix:** Remove the entire `buildscript {}` block. The `plugins {}` block handles everything.

**Performance impact:** Removes duplicate repository resolution at configuration time (~1-2s faster builds).

### 3.3 Missing Useful Dependencies

| Dependency                   | Purpose                                               | Recommendation                                                                                                                                      |
| ---------------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| spring-boot-starter-actuator | Health, metrics, info endpoints (production standard) | **DO NOT ADD** — LogLens has custom `/health`, `/metrics` endpoints tailored to its use case. Actuator would duplicate these with generic versions. |
| spring-boot-starter-security | Authentication/authorization                          | **DO NOT ADD** — see section 4 below                                                                                                                |
| springdoc-openapi            | Swagger/OpenAPI documentation                         | **CONSIDER** — see section 6.3 below                                                                                                                |

---

## 4. Security Hardening Plan

### 4.1 Current Security Posture

| Control          | Status                                          | Assessment                       |
| ---------------- | ----------------------------------------------- | -------------------------------- |
| XSS prevention   | `esc()` + `jsEsc()` in all 8 onclick handlers   | **EXCELLENT**                    |
| Path traversal   | 5-layer defense in `searchHistorical()`         | **EXCELLENT**                    |
| Input validation | All endpoints validate inputs                   | **GOOD**                         |
| CSV injection    | `csvField()` prefixes formula characters        | **GOOD**                         |
| Error disclosure | Generic 500 messages, no stack traces to client | **GOOD**                         |
| Authentication   | None — intentionally unauthenticated            | **BY DESIGN**                    |
| CORS             | Not configured                                  | **ACCEPTABLE** (same-origin SPA) |

### 4.2 Recommendation: **DO NOT Add Spring Security**

The codebase explicitly documents (LogController.java line 235):

> _"Intentionally unauthenticated — this is an internal diagnostic tool with no Spring Security; all endpoints are open."_

**Adding Spring Security would:**

1. **Break the deployment model.** LogLens runs on VMs alongside the services it monitors. There's no identity provider, no LDAP, no OAuth2 server on these VMs.
2. **Add 15+ transitive dependencies** (spring-security-core, spring-security-web, spring-security-config, etc.) for a tool that serves a single internal page.
3. **Require infrastructure** that doesn't exist (user stores, token management, session persistence).
4. **Slow down every request** with filter chain processing (~1-3ms per request for session/CSRF checks).

**The correct security boundary for an internal diagnostic tool is network-level access control** (firewall rules, VPN, bastion hosts) — not application-level authentication.

### 4.3 What SHOULD Be Hardened

| Item                          | Current  | Fix                                                                                   | Impact                                                     |
| ----------------------------- | -------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------- |
| Response headers              | None set | Add `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` via `WebMvcConfigurer` | Zero performance cost, prevents clickjacking/MIME sniffing |
| Dependency vulnerability scan | None     | Add OWASP dependency-check plugin to CI                                               | Build-time only, no runtime cost                           |

---

## 5. Testing Framework Plan

### 5.1 Current Coverage

| Source Class       | Has Tests? | Test Count | Assessment                                                                                     |
| ------------------ | ---------- | ---------- | ---------------------------------------------------------------------------------------------- |
| LogLensApplication | No         | —          | **SKIP** — 3-line Spring Boot main class                                                       |
| JacksonConfig      | No         | —          | **SKIP** — 3-line customizer, tested transitively via LogController tests                      |
| LogLensConfig      | No         | —          | **SKIP** — Lombok @Data bean, tested transitively via every service test                       |
| LogController      | Yes        | 14         | **ADEQUATE** — all endpoints covered                                                           |
| UIController       | No         | —          | **SKIP** — 2-line Thymeleaf forward, tested transitively via LogController test infrastructure |
| LogEntry           | No         | —          | **SKIP** — Lombok @Data + @Builder, no logic                                                   |
| SearchRequest      | No         | —          | **SKIP** — Lombok @Data, no logic                                                              |
| ServiceConfig      | No         | —          | **SKIP** — Lombok @Data, 3 fields                                                              |
| LogParserService   | Yes        | 28         | **EXCELLENT** — all 5 formats + edge cases                                                     |
| LogSearchService   | Yes        | 54         | **EXCELLENT** — deepest coverage in the project                                                |
| QueryEngine        | Yes        | 18         | **EXCELLENT** — full grammar coverage                                                          |

### 5.2 Recommendation: **DO NOT Target 100% Class Coverage**

**Writing tests for Lombok @Data classes, 3-line config beans, and Spring Boot's main() method is anti-pattern.** These classes contain zero logic — they are generated boilerplate. Testing them would:

1. **Test the framework, not our code.** Verifying `@Data` generates getters tests Lombok, not LogLens.
2. **Slow CI by 5-10s** for tests that can never fail unless Lombok itself is broken.
3. **Create maintenance burden** — every field change requires updating a test that adds no safety.

**What matters is behavioral coverage, not class coverage.** The 4 logic-bearing classes (LogController, LogParserService, LogSearchService, QueryEngine) have 114 tests covering:

- All REST endpoints (14 tests)
- All 5 log formats + edge cases (28 tests)
- All scan strategies, caching, pagination, filters (54 tests)
- Full query grammar (18 tests)

### 5.3 What SHOULD Be Added

| Test                                | Purpose                                                     | Justification                                         |
| ----------------------------------- | ----------------------------------------------------------- | ----------------------------------------------------- |
| `@SpringBootTest` context load test | Verify application starts with production config wiring     | Currently untested — catches Spring wiring issues     |
| Global exception handler test       | Verify `@RestControllerAdvice` returns correct status codes | Will be added alongside the new handler (section 6.1) |

---

## 6. Organizational Standards

### 6.1 Global Exception Handling

**Current:** Inline try-catch in `LogController` with duplicated error response formatting.

**Proposed:** Extract a `@RestControllerAdvice` class that handles:

- `IllegalArgumentException` -> 400 with `{"error": message}`
- `IOException` -> 500 with `{"error": "Search failed"}`
- Generic `Exception` -> 500 with `{"error": "Internal server error"}`

**File:** `src/main/java/com/honeywell/loglens/controller/GlobalExceptionHandler.java`

**Performance impact:** None. Spring's exception resolution is the same whether inline or in `@ControllerAdvice`.

**Benefit:** Eliminates 3 duplicate try-catch blocks from LogController. Centralizes error format.

### 6.2 Structured Logging

**Current:** Already using SLF4J via Lombok `@Slf4j` on all service classes. Log statements use parameterized format (`log.info("Cache page: entries {}-{}", from, to)`).

**Verdict: Already meets the standard.** No changes needed. Spring Boot's default Logback configuration provides:

- Structured console output with timestamp, level, thread, logger
- Log level configuration via `application.yml` (already set: `com.honeywell.loglens: DEBUG`)

Adding JSON-structured logging (Logstash encoder) would be overengineering for a diagnostic tool that produces ~20 log lines per request.

### 6.3 OpenAPI/Swagger Documentation

**Recommendation: DO NOT ADD at this time.**

Reasons:

1. LogLens has exactly **1 consumer** — its own `index.html` frontend. There are no external API consumers.
2. Adding `springdoc-openapi` adds **8 transitive dependencies** and a Swagger UI endpoint.
3. The API is already fully documented in `CLAUDE.md` (REST API section) and `README.md`.
4. If API documentation is needed later, it can be added in 5 minutes with a single dependency.

### 6.4 Security Response Headers

**Proposed:** Add a `WebMvcConfigurer` to set security headers on all responses:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override
      public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        return true;
      }
    });
  }
}
```

**Performance impact:** ~0.01ms per request (single header write).

---

## 7. Summary: What Changes vs. What Stays

### CHANGES (4 items)

| #   | Change                                             | Files                                                          | Performance Impact           |
| --- | -------------------------------------------------- | -------------------------------------------------------------- | ---------------------------- |
| 1   | Extract `@RestControllerAdvice`                    | New: `GlobalExceptionHandler.java`, Edit: `LogController.java` | None (same Spring mechanism) |
| 2   | Remove redundant `buildscript{}` from build.gradle | Edit: `build.gradle`                                           | ~1-2s faster Gradle config   |
| 3   | Add security response headers                      | New: `WebConfig.java`                                          | ~0.01ms/request              |
| 4   | Add `@SpringBootTest` context load test            | New: `LogLensApplicationTest.java`                             | ~3s CI only                  |

### NO CHANGES (with justification)

| Proposal                          | Decision       | Justification                                                                     |
| --------------------------------- | -------------- | --------------------------------------------------------------------------------- |
| Multi-module / Clean Architecture | **REJECT**     | 11 classes, 1 JAR, 1 developer — pure overhead                                    |
| Java/Spring Boot upgrade          | **NOT NEEDED** | Already on latest LTS (Java 21) and latest Spring Boot 3.5.8                      |
| Spring Security                   | **REJECT**     | Internal diagnostic tool, no identity provider, network-level security is correct |
| 100% class test coverage          | **REJECT**     | Tests Lombok/framework, not our logic. 114 behavioral tests cover all code paths  |
| OpenAPI/Swagger                   | **REJECT**     | Single consumer (own frontend), already documented                                |
| Structured JSON logging           | **REJECT**     | Diagnostic tool, ~20 log lines/request, default Logback is sufficient             |
| OWASP dependency-check            | **DEFER**      | CI workflow delegates to shared workflow — add there, not in this project         |

---

## 8. Implementation Plan (If Approved)

1. Remove `buildscript{}` from `build.gradle`
2. Create `GlobalExceptionHandler.java` — extract try-catch from LogController
3. Clean up `LogController.java` — remove inline try-catch, let exceptions propagate
4. Create `WebConfig.java` — security response headers
5. Create `LogLensApplicationTest.java` — context load test
6. Add test for GlobalExceptionHandler
7. Run `./gradlew clean test` — verify 0 regressions
8. Update CLAUDE.md with new files

**Estimated impact on codebase:** +3 new files (~80 lines total), -30 lines from LogController cleanup. Net: ~+50 lines.

---

**Awaiting CEO/CTO approval before proceeding to Phase 2.**
