package com.teamgehem.kyo_test

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.string.*
import kyo.*

import com.teamgehem.ironkyo.*

// ─────────────────────────────────────────────────────────────────────────────
// 0. 공통 도메인 타입 정의
// ─────────────────────────────────────────────────────────────────────────────

type Username = String :| (MinLength[3] & MaxLength[20] & Alphanumeric)
type Age = Int :| (Positive & Less[150])
type Email = String :| Match["^[^@]+@[^@]+$"]
type Score = Double :| (GreaterEqual[0.0] & LessEqual[100.0])
type UserId = Int :| Positive

case class User(
  id: UserId,
  name: Username,
  age: Age,
  email: Email,
)

sealed trait AppError
case class UserNotFound(id: Int) extends AppError
case class DuplicateUsername(name: String) extends AppError
case class ValidationFailed(e: ConstraintError) extends AppError

// ─────────────────────────────────────────────────────────────────────────────
// 1. 기본 사용 — 단일 값 검증 (short-circuit)
// ─────────────────────────────────────────────────────────────────────────────

// @SuppressWarnings(Array("org.wartremover.warts.Any"))
object BasicExample:
  val validAge: Age =
    25
  // val invalidAge: Age = -1  // ← 컴파일 에러

  def parseAge(raw: Int): Age < Abort[ConstraintError] =
    raw.refineAbort[Positive & Less[150]]

  // Abort 는 첫 실패에서 단락(short-circuit)
  def parseUser(
    rawName: String,
    rawAge: Int,
    rawEmail: String,
  ): String < Abort[ConstraintError] =
    for
      name <- rawName.refineAbort[MinLength[3] & MaxLength[20] & Alphanumeric]
      age <- rawAge.refineAbort[Positive & Less[150]]
      email: String <- rawEmail.refineAbort[Match["^[^@]+@[^@]+$"]]
    yield s"Parsed: $name, age=$age, email=$email"

// ─────────────────────────────────────────────────────────────────────────────
// 2. 에러 누적 — validateAll DSL 사용
// ─────────────────────────────────────────────────────────────────────────────

object AccumulateExample:

  // Before: 30줄짜리 보일러플레이트
  // After:  field(...).as[...] + validateAll + .map(Case.apply)
  def validateUser(
    rawId: Int,
    rawName: String,
    rawAge: Int,
    rawEmail: String,
  ): User < Abort[AggregatedConstraintError] =
    validateAll(
      field(rawId).as[Positive],
      field(rawName).as[MinLength[3] & MaxLength[20] & Alphanumeric],
      field(rawAge).as[Positive & Less[150]],
      field(rawEmail).as[Match["^[^@]+@[^@]+$"]],
    ).into[User]

// ─────────────────────────────────────────────────────────────────────────────
// 3. 커스텀 에러 타입으로 변환
// ─────────────────────────────────────────────────────────────────────────────

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object CustomErrorExample:
  def parseEmail(raw: String): Email < Abort[AppError] =
    raw.refineAbortWith[Match["^[^@]+@[^@]+$"], AppError](msg =>
      ValidationFailed(ConstraintError(msg, raw, "String"))
    )

  def findOrCreate(
    rawId: Int,
    db: Map[Int, User],
  ): User < Abort[ConstraintError | AppError] =
    for
      id <- rawId.refineAbort[Positive]
      user <- Abort.get(db.get(id).toRight(UserNotFound(id)))
    yield user

// ─────────────────────────────────────────────────────────────────────────────
// 4. Result 처리
// ─────────────────────────────────────────────────────────────────────────────

// @SuppressWarnings(Array("org.wartremover.warts.Any"))
object HandlingExample:
  def runWithResult(rawAge: Int): Result[ConstraintError, Age] < Sync =
    Abort.run[ConstraintError](rawAge.refineAbort[Positive & Less[150]])

  def parseAgeOrDefault(rawAge: Int, default: Age): Age < Sync =
    Abort
      .run[ConstraintError](rawAge.refineAbort[Positive & Less[150]])
      .map:
        case Result.Success(age) => age
        case Result.Failure(_) => default
        case Result.Panic(t) => throw t

// ─────────────────────────────────────────────────────────────────────────────
// 5. 실행 진입점
// ─────────────────────────────────────────────────────────────────────────────

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object IronKyoExamplesMain extends KyoApp:
  def section1: Unit < Sync =
    for
      _ <- Console.printLine("=== 1. Basic — short-circuit ===")
      r1 <- Abort.run[ConstraintError](BasicExample.parseAge(25))
      _ <- Console.printLine(s"age=25  → $r1")
      r2 <- Abort.run[ConstraintError](BasicExample.parseAge(-3))
      _ <- Console.printLine(s"age=-3  → $r2")
    yield ()

  def section2: Unit < Sync =
    for
      _ <- Console.printLine("\n=== 2. Chain — first failure stops ===")
      r3 <- Abort.run[ConstraintError](BasicExample.parseUser("alice", 30, "alice@example.com"))
      _ <- Console.printLine(s"valid  → $r3")
      r4 <- Abort.run[ConstraintError](BasicExample.parseUser("a", -1, "not-an-email"))
      _ <- Console.printLine(s"bad    → $r4")
    yield ()

  def section3: Unit < Sync =
    for
      _ <- Console.printLine("\n=== 3. validateAll — collect all errors ===")
      r5 <-
        Abort.run[AggregatedConstraintError](
          AccumulateExample.validateUser(1, "alice", 30, "alice@example.com")
        )
      _ <- Console.printLine(s"valid  → $r5")
      r6 <-
        Abort.run[AggregatedConstraintError](
          AccumulateExample.validateUser(-1, "x", -5, "not-email")
        )
      _ <- Console.printLine(s"errors → $r6")
    yield ()

  def section4: Unit < Sync =
    for
      _ <- Console.printLine("\n=== 4. Custom error type ===")
      r7 <- Abort.run[AppError](CustomErrorExample.parseEmail("user@domain.com"))
      _ <- Console.printLine(s"valid  → $r7")
      r8 <- Abort.run[AppError](CustomErrorExample.parseEmail("no-at-sign"))
      _ <- Console.printLine(s"bad    → $r8")
    yield ()

  def section5: Unit < Sync =
    for
      _ <- Console.printLine("\n=== 5. Result handling ===")
      r9 <- HandlingExample.runWithResult(42)
      _ <- Console.printLine(s"age=42 → $r9")
      r10 <- HandlingExample.runWithResult(-1)
      _ <- Console.printLine(s"age=-1 → $r10")
    yield ()

  run {
    for
      _ <- section1
      _ <- section2
      _ <- section3
      _ <- section4
      _ <- section5
    yield ()
  }
