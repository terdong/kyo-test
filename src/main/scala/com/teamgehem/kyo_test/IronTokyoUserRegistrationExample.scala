package com.teamgehem.kyo_test

import java.util.concurrent.atomic.AtomicReference

import scala.util.*

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.string.*
import kyo.*

import com.teamgehem.ironkyo.*
import com.teamgehem.tokyo.*

// ═════════════════════════════════════════════════════════════════════════════
//  IronKyo + Tokyo Combined Practical Example
//
//  IronKyo : 컴파일 타임 제약 검증 (Iron refined types + Kyo Abort)
//  Tokyo   : 이펙트 확장 (toAbort, catchPanic, mapAbort, tap, tapError,
//            recover, ensuring, orElse, provideLayer, toEither)
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
// 1. Domain Types — Iron Refined Types for Type-Safe Validation
// ─────────────────────────────────────────────────────────────────────────────

// 사용자명: 2~30자 알파벳+숫자
type CombinedUsername = String :| (MinLength[2] & MaxLength[30] & Alphanumeric)

// 이메일: 기본 이메일 형식
type CombinedEmail = String :| Match["^[^@]+@[^@]+\\.[^@]+$"]

// 나이: 1~150
type CombinedAge = Int :| (Positive & Less[150])

// 사용자 ID: 양수
type CombinedUserId = Int :| Positive

// 도메인 모델 — Iron 타입으로 불변 보장
final case class CombinedUser(
  id: CombinedUserId,
  name: CombinedUsername,
  email: CombinedEmail,
  age: CombinedAge,
)

// 가입 요청 (raw 입력값, 아직 검증되지 않음)
final case class CombinedRegRequest(
  name: String,
  email: String,
  age: Int,
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. Domain Errors
// ─────────────────────────────────────────────────────────────────────────────

sealed trait CombinedError
final case class CombinedValidationError(fieldErrors: List[String]) extends CombinedError
final case class CombinedUserAlreadyExists(email: String) extends CombinedError
final case class CombinedDbError(msg: String) extends CombinedError
final case class CombinedNotificationError(msg: String) extends CombinedError

// ─────────────────────────────────────────────────────────────────────────────
// 3. Mock Dependency Services
// ─────────────────────────────────────────────────────────────────────────────

trait CombinedDbService:
  def findByEmail(email: CombinedEmail): Option[CombinedUser] < Sync
  def save(user: CombinedUser): Unit < Sync
  def close(): Unit < Sync

final case class CombinedDbConfig(url: String)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
final class CombinedLiveDb(_config: CombinedDbConfig) extends CombinedDbService:
  private val _ =
    _config
  private val store =
    new AtomicReference(Map.empty[String, CombinedUser])

  override def findByEmail(email: CombinedEmail): Option[CombinedUser] < Sync =
    Console.printLine(s"  🔍 [DB] Looking up: $email").map { _ =>
      store.get().get(email)
    }

  override def save(user: CombinedUser): Unit < Sync =
    if user.name.contains("Crash") then
      Abort.panic(new RuntimeException("Disk I/O failure: volume is read-only!"))
    else
      Console.printLine(s"  💾 [DB] Persisted: ${user.name} <${user.email}>").map { _ =>
        store.updateAndGet(m => m + (user.email -> user))
        ()
      }

  override def close(): Unit < Sync =
    Console.printLine("  🔌 [DB] Connection released")

trait CombinedEmailService:
  def sendWelcome(email: CombinedEmail, name: CombinedUsername): Try[Unit] < Sync

final case class CombinedEmailConfig(smtpHost: String)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
final class CombinedLiveEmail(_config: CombinedEmailConfig) extends CombinedEmailService:
  private val _ =
    _config
  override def sendWelcome(email: CombinedEmail, name: CombinedUsername): Try[Unit] < Sync =
    Console.printLine(s"  ✉️  [Email] Sending welcome to $name <$email>").map { _ =>
      if email.contains("fail-email") then Failure(new RuntimeException("SMTP connection refused"))
      else Success(())
    }

// Layer 환경 구성
final case class CombinedAppEnv(db: CombinedDbService, email: CombinedEmailService)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object CombinedAppEnv:
  val layer: Layer[CombinedAppEnv, Any] =
    Layer {
      CombinedAppEnv(
        CombinedLiveDb(CombinedDbConfig("jdbc:postgresql://localhost:5432/combined_db")),
        CombinedLiveEmail(CombinedEmailConfig("smtp.example.com")),
      )
    }

// ─────────────────────────────────────────────────────────────────────────────
// 4. Registration Service — IronKyo + Tokyo 결합
// ─────────────────────────────────────────────────────────────────────────────

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object CombinedRegistrationService:

  // ┌─────────────────────────────────────────────────────────────────────────┐
  // │ IronKyo: validateAll로 모든 필드를 한 번에 검증하고, 에러를 누적 수집    │
  // │ Tokyo:   mapAbort로 IronKyo 에러를 도메인 에러로 변환                   │
  // └─────────────────────────────────────────────────────────────────────────┘
  private def validateRequest(req: CombinedRegRequest): CombinedUser < Abort[CombinedError] =
    validateAll(
      field(scala.util.Random.nextInt(9999) + 1).as[Positive],
      field(req.name).as[MinLength[2] & MaxLength[30] & Alphanumeric],
      field(req.email).as[Match["^[^@]+@[^@]+\\.[^@]+$"]],
      field(req.age).as[Positive & Less[150]],
    ).into[CombinedUser]
      .mapAbort: aggErr =>
        val msgs = aggErr.errors.map(e => s"'${e.inputValue}': ${e.message}")
        CombinedValidationError(msgs)

  // ┌─────────────────────────────────────────────────────────────────────────┐
  // │ Tokyo: toAbort로 Option → Abort 변환, 중복 체크                        │
  // └─────────────────────────────────────────────────────────────────────────┘
  private def ensureNotDuplicate(
    db: CombinedDbService,
    email: CombinedEmail,
  ): Unit < (Abort[CombinedError] & Sync) =
    for
      existing <- db.findByEmail(email)
      _ <-
        existing match
          case Some(_) => Abort.fail(CombinedUserAlreadyExists(email))
          case None => Kyo.unit
    yield ()

  // ┌─────────────────────────────────────────────────────────────────────────┐
  // │ Tokyo: catchPanic — DB 런타임 패닉을 도메인 에러로 포획                  │
  // │ Tokyo: mapAbort + tap + tapError + recover — 이메일 실패 우아하게 처리   │
  // │ Tokyo: ensuring — 반드시 DB 커넥션 정리                                 │
  // └─────────────────────────────────────────────────────────────────────────┘
  def register(
    req: CombinedRegRequest
  ): CombinedUser < (Abort[CombinedError] & Env[CombinedAppEnv] & Sync) =
    for
      env <- Env.use[CombinedAppEnv](identity)
      db = env.db
      email = env.email

      // 1. IronKyo: 모든 필드 제약 조건 검증 (에러 누적)
      user <- validateRequest(req)

      // 2. Tokyo: 중복 유저 체크
      _ <- ensureNotDuplicate(db, user.email)

      // 3. Tokyo: catchPanic — JVM 런타임 예외를 도메인 에러로 전환
      _ <-
        db.save(user)
          .catchPanic(ex => CombinedDbError(s"DB Panic: ${ex.getMessage}"))

      // 4. Tokyo: toAbort + mapAbort + tap + tapError + recover
      //    이메일 발송 실패해도 가입은 성공
      _ <-
        email
          .sendWelcome(user.email, user.name)
          .toAbort
          .mapAbort(ex => CombinedNotificationError(s"Email failed: ${ex.getMessage}"))
          .tap(_ => Console.printLine(s"  ✨ [System] Welcome email sent to ${user.email}"))
          .tapError(err => Console.printLine(s"  ⚠️  [System] Email failed: $err"))
          .recover(_ => ()) // 이메일 실패 무시
    yield user

  // ┌─────────────────────────────────────────────────────────────────────────┐
  // │ Tokyo: ensuring — DB 연결 정리 보장                                     │
  // │ Tokyo: provideLayer — 의존성 주입                                       │
  // └─────────────────────────────────────────────────────────────────────────┘
  def registerWithCleanup(
    req: CombinedRegRequest
  ): CombinedUser < (Abort[CombinedError] & Env[CombinedAppEnv] & Sync) =
    for
      env <- Env.use[CombinedAppEnv](identity)
      res <- register(req).ensuring(env.db.close())
    yield res

  // ┌─────────────────────────────────────────────────────────────────────────┐
  // │ Tokyo: orElse — 알림 채널 폴백                                         │
  // └─────────────────────────────────────────────────────────────────────────┘
  def notifyPrimary(email: String): Unit < (Abort[CombinedNotificationError] & Sync) =
    if email.contains("primary-fail") then
      Abort.fail(CombinedNotificationError("Primary channel down"))
    else Console.printLine(s"  📡 [Notification] Sent via Primary to $email")

  def notifyBackup(email: String): Unit < (Abort[CombinedNotificationError] & Sync) =
    Console.printLine(s"  📡 [Notification] Sent via Backup to $email")

  def dispatchNotification(email: String): Unit < (Abort[CombinedNotificationError] & Sync) =
    notifyPrimary(email).orElse(notifyBackup(email))

// ─────────────────────────────────────────────────────────────────────────────
// 5. Main Running App
// ─────────────────────────────────────────────────────────────────────────────

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object IronTokyoUserRegistrationExampleMain extends KyoApp:
  private def runScenario(
    name: String,
    req: CombinedRegRequest,
  ): Unit < Sync =
    val program =
      CombinedRegistrationService
        .registerWithCleanup(req)
        .provideLayer(CombinedAppEnv.layer)

    Memo.run {
      for
        _ <- Console.printLine(s"\n─── $name ───")
        result <- Abort.run(program)
        _ <- Console.printLine(s"  📋 Result: ${result.toEither}")
      yield ()
    }

  private def runNotificationScenario(
    name: String,
    email: String,
  ): Unit < Sync =
    for
      _ <- Console.printLine(s"\n─── $name ───")
      result <- Abort.run(CombinedRegistrationService.dispatchNotification(email))
      _ <- Console.printLine(s"  📋 Result: ${result.toEither}")
    yield ()

  run {
    for
      _ <- Console.printLine("═" * 65)
      _ <- Console.printLine("  IronKyo + Tokyo Combined Practical Example")
      _ <- Console.printLine("  ─ Iron refined types for compile-time safety")
      _ <- Console.printLine("  ─ Tokyo extensions for ergonomic effect handling")
      _ <- Console.printLine("═" * 65)

      // 시나리오 1: 정상 가입 (IronKyo 검증 통과 + Tokyo 효과 체인)
      _ <-
        runScenario(
          name = "1. Successful Registration (IronKyo ✓ + Tokyo ✓)",
          req = CombinedRegRequest("Darren", "darren@example.com", 30),
        )

      // 시나리오 2: IronKyo 검증 실패 — 복수 필드 에러 누적
      _ <-
        runScenario(
          name = "2. Validation Failure — Error Accumulation (IronKyo validateAll)",
          req = CombinedRegRequest("", "not-an-email", -5),
        )

      // 시나리오 3: IronKyo 검증 통과, 이메일 발송 실패 → Tokyo recover로 우아한 복구
      _ <-
        runScenario(
          name = "3. Email Failure — Graceful Recovery (Tokyo recover)",
          req = CombinedRegRequest("Bob", "bob@fail-email.com", 28),
        )

      // 시나리오 4: DB 런타임 패닉 → Tokyo catchPanic으로 포획
      _ <-
        runScenario(
          name = "4. DB Panic — Runtime Exception Recovery (Tokyo catchPanic)",
          req = CombinedRegRequest("CrashUser", "crash@example.com", 35),
        )

      // 시나리오 5: 알림 채널 폴백 (Tokyo orElse)
      _ <-
        runNotificationScenario(
          name = "5. Notification — Primary Succeeds (Tokyo orElse)",
          email = "alice@example.com",
        )

      _ <-
        runNotificationScenario(
          name = "6. Notification — Fallback to Backup (Tokyo orElse)",
          email = "bob@primary-fail.com",
        )

      _ <- Console.printLine("\n" + "═" * 65)
    yield ()
  }
