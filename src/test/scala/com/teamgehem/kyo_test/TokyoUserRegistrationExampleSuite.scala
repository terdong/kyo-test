package com.teamgehem.kyo_test

import kyo.*

import com.teamgehem.kyo_test.TestSuite
import com.teamgehem.tokyo.*
import com.teamgehem.tokyo.testkit.TestKyoExtensions.*

final class TokyoUserRegistrationExampleSuite extends TestSuite:
  test("successful registration"):
    val req = TokyoUserRegRequest("Darren", "darren@example.com", 30)
    val program =
      UserRegistrationService
        .registerWithCleanup(req)
        .provideLayer(AppEnv.layer)

    val eitherResult = program.runSync

    expect(eitherResult.isRight)
    eitherResult match
      case Right(user) =>
        expectEquals(user.name, "Darren")
        expectEquals(user.email, "darren@example.com")
        expectEquals(user.age, 30)
      case Left(_) =>
        fail("Registration failed")

  test("validation failure - invalid email"):
    val req = TokyoUserRegRequest("Alice", "invalid-email", 25)
    val program =
      UserRegistrationService
        .registerWithCleanup(req)
        .provideLayer(AppEnv.layer)

    val eitherResult = program.runSync

    expect(eitherResult match
      case Left(InvalidInput("Email format is invalid")) => true
      case _ => false)

  test("validation failure - empty name"):
    val req = TokyoUserRegRequest("", "alice@example.com", 25)
    val program =
      UserRegistrationService
        .registerWithCleanup(req)
        .provideLayer(AppEnv.layer)

    val eitherResult = program.runSync

    expect(eitherResult match
      case Left(InvalidInput("Name cannot be empty")) => true
      case _ => false)

  test("registration succeeds even if email service fails"):
    val req = TokyoUserRegRequest("Bob", "bob@fail-email.com", 28)
    val program =
      UserRegistrationService
        .registerWithCleanup(req)
        .provideLayer(AppEnv.layer)

    val eitherResult = program.runSync

    expect(eitherResult.isRight)

  test("database runtime panic recovery"):
    val req = TokyoUserRegRequest("Crash User", "crash@example.com", 35)
    val program =
      UserRegistrationService
        .registerWithCleanup(req)
        .provideLayer(AppEnv.layer)

    val eitherResult = program.runSync

    expect {
      eitherResult match
        case Left(DbPersistenceError(errorMsg))
             if errorMsg.contains("Database disk partition is full!") =>
          true
        case _ => false
    }

  test("notification fallback (orElse)"):
    val primaryProgram = UserRegistrationService.dispatchNotification("test@example.com")
    val eitherResult = primaryProgram.runSync

    expect(eitherResult.isRight)

  test("notification fallback backup channel"):
    val fallbackProgram = UserRegistrationService.dispatchNotification("test@primary-fail.com")
    val eitherResult = fallbackProgram.runSync

    expect(eitherResult.isRight)
