# Kyo, Iron & Tokyo Integration Example Project (`kyo_test`)

This project demonstrates various development patterns and best practices for building safe and flexible business logic in the **Scala 3** environment. It integrates **Kyo** (a next-generation effect system), **Iron** (a type-level constraint validation library), and **Tokyo** (utility extensions providing ZIO-like effect handling capabilities).

---

## 🛠️ Technology Stack

- **Language**: Scala 3 (v3.8.4)
- **Effect System**: [Kyo](https://github.com/getkyo/kyo) (v1.0.0-RC4) - High-performance, low-latency algebraic effect system for Scala.
- **Validation**: [Iron](https://github.com/Iltotore/iron) (v3.3.1) - Type-level constraint validation library.
- **Integration**:
  - **IronKyo** (v0.1.2) - Integration library linking Iron's validation model with Kyo's `Abort` effect.
  - **Tokyo** (v0.1.2) - Infrastructural utility library providing ZIO-like ergonomic extensions (DI Layer, catchPanic, ensuring, orElse, recover, etc.) built on top of Kyo.
- **Testing**: MUnit, ScalaCheck (Property-based testing), [Expecty](https://github.com/eed3si9n/expecty) (Power assertions)

---

## 📂 Project Structure & File Descriptions

```text
kyo_test/
├── project/                        # SBT build helpers and plugin definitions
│   ├── Dependencies.scala          # Project external dependency definitions
│   └── MyUtil.scala                # SBT shell prompt and styling utilities
├── src/
│   ├── main/scala/com/teamgehem/kyo_test/
│   │   ├── IronKyoExamples.scala              # Basic Iron & Kyo integration and validation DSL examples
│   │   ├── IronKyoOrderProcessingExample.scala# E-commerce order processing business logic with IronKyo
│   │   ├── TokyoUserRegistrationExample.scala  # Demonstrations of various Tokyo ergonomic effect extensions
│   │   ├── IronTokyoUserRegistrationExample.scala # Combined example of IronKyo validation + Tokyo effect control
│   │   ├── KyoDirectNowLaterExample.scala     # Understanding .now vs .later and lazy execution in kyo-direct
│   │   └── KyoDirectOrderProcessingExample.scala # Imperative-style order processing using kyo-direct
│   └── test/scala/com/teamgehem/kyo_test/
│       ├── TestSuite.scala                    # Base test trait integrating MUnit, ScalaCheck, and Expectations
│       ├── Expectations.scala                 # Power assertions helper leveraging Expecty
│       ├── BasicExampleTestSuite.scala        # Property-based testing validation test
│       └── TokyoUserRegistrationExampleSuite.scala # Unit tests for Tokyo-based user registration service
├── aliases.sbt                     # SBT command aliases for developer convenience
├── build.sbt                       # Main SBT build definition file
├── sbt.sbt                         # SBT build pipelining and compilation speed optimization settings
├── scalac.sbt                      # Strict compiler options (Strict Equality, Explicit Nulls) for Scala 3
└── scalafix.sbt                    # SemanticDB settings for the Scalafix linter tool
```

### 1. SBT Build Configurations

*   [build.sbt](file:///home/darren/project-scala/kyo_test/build.sbt): The main entry point for the build configuration. Declares module dependencies (`iron`, `kyo-core`, `kyo-direct`, `ironkyo`, `tokyo`).
*   [aliases.sbt](file:///home/darren/project-scala/kyo_test/aliases.sbt): Provides short aliases for frequently used SBT commands.
    *   `c` $\rightarrow$ `compile`
    *   `t` $\rightarrow$ `test`
    *   `styleFix` $\rightarrow$ `scalafixAll; scalafmtSbt; scalafmtAll` (Formats code and applies linter rules)
*   [scalac.sbt](file:///home/darren/project-scala/kyo_test/scalac.sbt): Sets strict compiler warnings and configurations for Scala 3 (e.g., `-Yexplicit-nulls`, `-Wsafe-init`, `strictEquality`).
*   [sbt.sbt](file:///home/darren/project-scala/kyo_test/sbt.sbt): Disables parallel test execution, enables pipelined compilation (`usePipelining`), turns on turbo mode, and customizes prompt logs.
*   [scalafix.sbt](file:///home/darren/project-scala/kyo_test/scalafix.sbt): Enables the semanticdb compiler plugin required by Scalafix for static analysis.

---

## 💡 Core Concepts & Example Code Highlights

### 🟢 Core Examples (`src/main/scala`)

#### 1. [IronKyoExamples.scala](file:///home/darren/project-scala/kyo_test/src/main/scala/com/teamgehem/kyo_test/IronKyoExamples.scala)
Demonstrates validation of runtime values using Iron constraint types and Kyo's `Abort` effect.
-   **Short-circuit Validation**: Refines a single input value via `raw.refineAbort[Constraint]`. Fails immediately with `Abort[ConstraintError]`, stopping subsequent execution.
-   **Error Accumulation**: Validates multiple fields at once using the `validateAll` DSL. If any validation fails, it collects all constraint errors into an `AggregatedConstraintError` and maps them into a case class using `.into[User]`.
-   **Custom Error Mapping**: Uses `refineAbortWith` to transform validation failures into domain-specific error types (`ValidationFailed`).

#### 2. [IronKyoOrderProcessingExample.scala](file:///home/darren/project-scala/kyo_test/src/main/scala/com/teamgehem/kyo_test/IronKyoOrderProcessingExample.scala)
An e-commerce order processing pipeline using IronKyo.
-   Defines constraints on domain types: `OrderId` (regex match), `Quantity` (range 1~100), `Price` (positive double), and `Phone` (phone number format).
-   Aggregates input errors using `validateAll`. If valid, it flows into steps like checking blacklists, checking inventory, and processing payment. The steps are sequentially sequenced inside a Kyo for-comprehension using the `Abort[OrderError]` effect.

#### 3. [TokyoUserRegistrationExample.scala](file:///home/darren/project-scala/kyo_test/src/main/scala/com/teamgehem/kyo_test/TokyoUserRegistrationExample.scala)
Showcases various ergonomic extensions provided by the **Tokyo** library for Kyo.
-   **`Option.toAbort`**: Lifts an `Option[A]` into a `Abort[E]` effect, converting `None` to a specified domain error.
-   **`catchPanic`**: Catches unexpected JVM runtime exceptions (Panics) during database interactions and translates them into domain errors (`DbPersistenceError`).
-   **`toAbort / mapAbort / tap / tapError / recover`**: Converts a `Try` into an `Abort` effect, applies side-effects (e.g. logging) on success (`tap`) or failure (`tapError`), and gracefully handles non-blocking errors (e.g. welcome email failures) via `recover` without breaking the main workflow.
-   **`ensuring`**: Guarantees that resource cleanup (such as closing database connections) is executed regardless of success or failure.
-   **`orElse`**: Runs a fallback effect (such as backup notification channels) if the primary effect fails.
-   **`provideLayer`**: Injects dependency environments (`Layer[AppEnv, Any]`) into an `Env[AppEnv]` effect program.

#### 4. [IronTokyoUserRegistrationExample.scala](file:///home/darren/project-scala/kyo_test/src/main/scala/com/teamgehem/kyo_test/IronTokyoUserRegistrationExample.scala)
Combines **IronKyo** and **Tokyo** in a single end-to-end user registration workflow.
-   Inputs are validated using IronKyo's `validateAll`.
-   The business logic leverages Tokyo's `catchPanic` for DB safety, `ensuring` for connection cleanup, `recover` for SMTP errors, and `orElse` for notification fallbacks.

#### 5. [KyoDirectNowLaterExample.scala](file:///home/darren/project-scala/kyo_test/src/main/scala/com/teamgehem/kyo_test/KyoDirectNowLaterExample.scala)
Explains the usage of `.now` and `.later` within `kyo-direct` blocks.
-   **`.now`**: Unwraps an effect (e.g., `String < Sync`) in a `direct` block, allowing developers to bind and use the plain underlying value (`String`).
-   **`.later`**: Prevents the direct macro from automatically unwrapping the expression, preserving the raw effectful wrapper (`String < Sync`) for deferred or lazy execution.
-   **Use Cases**: Useful for conditional effect execution and combining/nesting pending effects.

#### 6. [KyoDirectOrderProcessingExample.scala](file:///home/darren/project-scala/kyo_test/src/main/scala/com/teamgehem/kyo_test/KyoDirectOrderProcessingExample.scala)
Re-implements the e-commerce order processing logic using `kyo-direct` instead of for-comprehensions.
-   Applies `.now` sequentially on each line, creating a clean, imperative-looking style for writing algebraic effect flows.

---

### 🧪 Test Suite (`src/test/scala`)

*   [TestSuite.scala](file:///home/darren/project-scala/kyo_test/src/test/scala/com/teamgehem/kyo_test/TestSuite.scala) & [Expectations.scala](file:///home/darren/project-scala/kyo_test/src/test/scala/com/teamgehem/kyo_test/Expectations.scala): Custom test suite utilizing MUnit, ScalaCheck, and Expecty. Enables visual power assertions that print detailed intermediate expression states upon failure.
*   [BasicExampleTestSuite.scala](file:///home/darren/project-scala/kyo_test/src/test/scala/com/teamgehem/kyo_test/BasicExampleTestSuite.scala): A simple demonstration of property-based testing using `forAll`.
*   [TokyoUserRegistrationExampleSuite.scala](file:///home/darren/project-scala/kyo_test/src/test/scala/com/teamgehem/kyo_test/TokyoUserRegistrationExampleSuite.scala): Unit tests validating successful registration, validation failures, email failure recovery, database panic isolation, and fallback channels. Runs Kyo effects synchronously in testing via the `program.runSync` helper.

---

## 🚀 How to Run the Project

You can run these examples and tests directly through the SBT console.

> [!NOTE]
> Upon entering the SBT shell, a list of defined command aliases will be shown.

### 1. Enter the SBT Console
```bash
sbt
```

### 2. Compile All Sources
```text
kyo_test> c
```

### 3. Run All Tests
```text
kyo_test> t
```

### 4. Execute a Specific Example
Run the interactive runner to execute main examples:
```text
kyo_test> r

Multiple main classes detected. Select one:
 [1] com.teamgehem.kyo_test.IronKyoExamplesMain
 [2] com.teamgehem.kyo_test.IronKyoOrderProcessingExampleMain
 [3] com.teamgehem.kyo_test.IronTokyoUserRegistrationExampleMain
 [4] com.teamgehem.kyo_test.KyoDirectNowLaterExampleMain
 [5] com.teamgehem.kyo_test.KyoDirectOrderProcessingExampleMain
 [6] com.teamgehem.kyo_test.TokyoUserRegistrationExampleMain
```
