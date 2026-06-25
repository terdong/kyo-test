package com.teamgehem.kyo_test

import java.util.concurrent.atomic.AtomicReference

import scala.util.*

import kyo.*

import com.teamgehem.tokyo.*

// ─────────────────────────────────────────────────────────────────────────────
// 1. Domain Types & Errors
// ─────────────────────────────────────────────────────────────────────────────

final case class TokyoUser(
  id: Int,
  name: String,
  email: String,
  age: Int,
)
final case class TokyoUserRegRequest(
  name: String,
  email: String,
  age: Int,
)

sealed trait RegistrationError
final case class InvalidInput(reason: String) extends RegistrationError
final case class UserAlreadyExists(email: String) extends RegistrationError
final case class DbPersistenceError(msg: String) extends RegistrationError
final case class NotificationFailed(msg: String) extends RegistrationError

// ─────────────────────────────────────────────────────────────────────────────
// 2. Mock Dependency Services
// ─────────────────────────────────────────────────────────────────────────────

trait DbService:
  def findUserByEmail(email: String): Option[TokyoUser] < Sync
  def saveUser(user: TokyoUser): Unit < Sync
  def closeConnection(): Unit < Sync

final case class DbConfig(url: String)

final class LiveDbService(config: DbConfig) extends DbService:
  private val db =
    new AtomicReference(Map.empty[String, TokyoUser])

  override def findUserByEmail(email: String): Option[TokyoUser] < Sync =
    Console.printLine(s"🔍 [DB] Searching user: $email").map { _ =>
      db.get().get(email)
    }

  override def saveUser(user: TokyoUser): Unit < Sync =
    if user.name.contains("Crash") then
      // Simulate unexpected runtime exception (to show catchPanic)
      Abort.panic(new RuntimeException("Database disk partition is full!"))
    else
      Console.printLine(s"💾 [DB] Saving user: ${user.name}").map { _ =>
        db.updateAndGet(current => current + (user.email -> user))
        ()
      }

  override def closeConnection(): Unit < Sync =
    Console.printLine("🔌 [DB] Connection closed cleanly").unit

trait EmailService:
  def sendWelcomeEmail(email: String, name: String): Try[Unit] < Sync

final case class EmailConfig(apiKey: String)

final class LiveEmailService(config: EmailConfig) extends EmailService:
  override def sendWelcomeEmail(email: String, name: String): Try[Unit] < Sync =
    Console.printLine(s"✉️ [Email] Dispatching to $name <$email>").map { _ =>
      if email.contains("fail-email") then Failure(new RuntimeException("SMTP Server Unreachable"))
      else Success(())
    }

// Consolidated Environment containing dependencies
final case class AppEnv(db: DbService, email: EmailService)

object AppEnv:
  val layer: Layer[AppEnv, Any] =
    Layer {
      AppEnv(
        LiveDbService(DbConfig("jdbc:postgresql://localhost:5432/tokyo_db")),
        LiveEmailService(EmailConfig("api-key-tokyo-123")),
      )
    }

// ─────────────────────────────────────────────────────────────────────────────
// 3. User Registration Core Service (Demonstrating Tokyo Extensions)
// ─────────────────────────────────────────────────────────────────────────────

object UserRegistrationService:

  // 1) Option.toAbort demonstration
  private def validateRequest(
    req: TokyoUserRegRequest
  ): TokyoUserRegRequest < Abort[RegistrationError] =
    val emailRegex = "^[^@]+@[^@]+$".r
    val emailOpt = Option(req.email).filter(emailRegex.matches)

    for
      // Converts Option[String] -> String < Abort[RegistrationError]
      _ <- emailOpt.toAbort(InvalidInput("Email format is invalid"))
      _ <-
        if req.name.trim.isEmpty then Abort.fail(InvalidInput("Name cannot be empty")) else Kyo.unit
      _ <- if req.age <= 0 then Abort.fail(InvalidInput("Age must be positive")) else Kyo.unit
    yield req

  // 2) Main registration logic demonstrating Env, catchPanic, mapAbort, tap, tapError, and recover
  def register(
    req: TokyoUserRegRequest
  ): TokyoUser < (Abort[RegistrationError] & Env[AppEnv] & Sync) =
    for
      env <- Env.use[AppEnv](identity)
      db = env.db
      email = env.email

      // Validate the request
      validReq <- validateRequest(req)

      // Check if duplicate user exists
      existingOpt <- db.findUserByEmail(validReq.email)
      _ <-
        existingOpt match
          case Some(_) => Abort.fail(UserAlreadyExists(validReq.email))
          case None => Kyo.unit

      userId = scala.util.Random.nextInt(10000)
      newUser = TokyoUser(userId, validReq.name, validReq.email, validReq.age)

      // Save user & catch JVM panic:
      // - db.saveUser returns Unit < Sync.
      // - .catchPanic catches runtime exceptions and shifts them to Abort[RegistrationError]
      _ <-
        db.saveUser(newUser)
          .catchPanic(ex => DbPersistenceError(s"DB Panic: ${ex.getMessage}"))

      // Send welcome email & handle failure:
      // - email.sendWelcomeEmail returns Try[Unit] < Sync.
      // - .toAbort lifts effectful Try to Abort[Throwable].
      // - .mapAbort maps Throwable to NotificationFailed domain error.
      // - .tap performs success side-effect.
      // - .tapError performs failure logging side-effect.
      // - .recover handles email failure gracefully, so registration succeeds even if email fails.
      _ <-
        email
          .sendWelcomeEmail(newUser.email, newUser.name)
          .toAbort
          .mapAbort(ex => NotificationFailed(s"Email Delivery Failure: ${ex.getMessage}"))
          .tap(_ => Console.printLine(s"✨ [System] Email sent successfully to ${newUser.email}"))
          .tapError(err => Console.printLine(s"⚠️ [System Warning] Welcome email failed: $err"))
          .recover(_ => ()) // Discharge/ignore error and continue
    yield newUser

  // 3) ensuring demonstration: guarantees that the database connection is closed after registration
  def registerWithCleanup(
    req: TokyoUserRegRequest
  ): TokyoUser < (Abort[RegistrationError] & Env[AppEnv] & Sync) =
    for
      env <- Env.use[AppEnv](identity)
      // Runs register, and ensures db.closeConnection() is always called at the end
      res <- register(req).ensuring(env.db.closeConnection())
    yield res

  // 4) orElse demonstration: fallback notifications
  def notifyPrimary(email: String): Unit < (Abort[NotificationFailed] & Sync) =
    if email.contains("primary-fail") then
      Abort.fail(NotificationFailed("Primary smtp server down"))
    else Console.printLine(s"📡 [Notification] Sent notification via Primary Channel to $email")

  def notifyBackup(email: String): Unit < (Abort[NotificationFailed] & Sync) =
    Console.printLine(s"📡 [Notification] Sent notification via Backup Channel to $email")

  def dispatchNotification(email: String): Unit < (Abort[NotificationFailed] & Sync) =
    // If notifyPrimary fails, fallback to notifyBackup
    notifyPrimary(email).orElse(notifyBackup(email))

// ─────────────────────────────────────────────────────────────────────────────
// 4. Main Running App
// ─────────────────────────────────────────────────────────────────────────────

object TokyoUserRegistrationExampleMain extends KyoApp:
  private def runScenario(
    name: String,
    req: TokyoUserRegRequest,
  ): Unit < Sync =
    // Wiring the environment dependencies using provideLayer
    val program =
      UserRegistrationService
        .registerWithCleanup(req)
        .provideLayer(AppEnv.layer)

    Memo.run {
      for
        _ <- Console.printLine(s"\n--- Scenario: $name ---")
        result <- Abort.run(program)
        _ <- Console.printLine(s"Result: ${result.toEither}") // Using toEither extension!
      yield ()
    }

  private def runNotificationScenario(
    name: String,
    email: String,
  ): Unit < Sync =
    for
      _ <- Console.printLine(s"\n--- Notification Scenario: $name ---")
      result <- Abort.run(UserRegistrationService.dispatchNotification(email))
      _ <- Console.printLine(s"Result: ${result.toEither}")
    yield ()

  run {
    for
      _ <- Console.printLine("=========================================================")
      _ <- Console.printLine("           Tokyo Library Practical Examples Demo         ")
      _ <- Console.printLine("=========================================================")

      // Scenario 1: Normal successful registration
      _ <-
        runScenario(
          name = "Success Registration",
          req = TokyoUserRegRequest("Darren", "darren@example.com", 30),
        )

      // Scenario 2: Validation Failure (Option.toAbort)
      _ <-
        runScenario(
          name = "Invalid Email Registration",
          req = TokyoUserRegRequest("Alice", "invalid_email_format", 25),
        )

      // Scenario 3: Email fails but Registration succeeds (recover)
      _ <-
        runScenario(
          name = "Email Sending Failure (Graceful Recovery)",
          req = TokyoUserRegRequest("Bob", "bob@fail-email.com", 28),
        )

      // Scenario 4: Database crash / Exception (catchPanic)
      _ <-
        runScenario(
          name = "Database Runtime Panic Recovery",
          req = TokyoUserRegRequest("Crash User", "crash@example.com", 35),
        )

      // Scenario 5: Fallback notification channel (orElse)
      _ <-
        runNotificationScenario(
          name = "Primary Notification Succeeds",
          email = "alice@example.com",
        )

      _ <-
        runNotificationScenario(
          name = "Primary Fails, Falling back to Backup",
          email = "bob@primary-fail.com",
        )

      _ <- Console.printLine("=========================================================")
    yield ()
  }
