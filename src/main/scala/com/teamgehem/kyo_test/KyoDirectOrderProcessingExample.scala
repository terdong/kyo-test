package com.teamgehem.kyo_test

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.string.*
import kyo.*

import com.teamgehem.ironkyo.*

// -----------------------------------------------------------------------------
// 1. Domain Constraints and Type Definitions (Domain Types with Iron Constraints)
// -----------------------------------------------------------------------------

type DirectOrderId = String :| Match["^ORD-\\d{8}-[A-Z0-9]{4}$"]
type DirectProductSku = String :| Match["^[A-Z]{3}-\\d{5}$"]
type DirectQuantity = Int :| (Positive & LessEqual[100])
type DirectPrice = Double :| Positive
type DirectPhone = String :| Match["^010-\\d{4}-\\d{4}$"]

// Valid Order Domain Model
final case class DirectValidatedOrder(
  id: DirectOrderId,
  sku: DirectProductSku,
  quantity: DirectQuantity,
  price: DirectPrice,
  phone: DirectPhone,
)

// -----------------------------------------------------------------------------
// 2. Business Error Definitions (Business Errors)
// -----------------------------------------------------------------------------

sealed trait DirectOrderError
final case class DirectValidationError(errors: List[String]) extends DirectOrderError
final case class DirectOutOfStock(
  sku: String,
  requested: Int,
  available: Int,
) extends DirectOrderError
final case class DirectBlockedUser(phone: String) extends DirectOrderError
final case class DirectPaymentFailed(amount: Double, reason: String) extends DirectOrderError

// -----------------------------------------------------------------------------
// 3. Business Service Implementation (Business Services using kyo-direct)
// -----------------------------------------------------------------------------

object DirectOrderService:
  private val stockDb =
    Map(
      "APP-10023" -> 50,
      "ELE-99882" -> 5,
      "CLO-55443" -> 0,
    )

  private val blockedPhones =
    Set(
      "010-0000-0000",
      "010-1234-5678",
    )

  // Step 1: Input Validation (kyo-direct style)
  @scala.annotation.nowarn("msg=pattern selector")
  def validate(
    rawId: String,
    rawSku: String,
    rawQty: Int,
    rawPrice: Double,
    rawPhone: String,
  ): DirectValidatedOrder < Abort[DirectOrderError] =
    direct:
      // Match directly on the Result from Abort.run
      val result =
        Abort
          .run[AggregatedConstraintError] {
            validateAll(
              field(rawId).as[Match["^ORD-\\d{8}-[A-Z0-9]{4}$"]],
              field(rawSku).as[Match["^[A-Z]{3}-\\d{5}$"]],
              field(rawQty).as[Positive & LessEqual[100]],
              field(rawPrice).as[Positive],
              field(rawPhone).as[Match["^010-\\d{4}-\\d{4}$"]],
            ).into[DirectValidatedOrder]
          }
          .now

      result match
        case Result.Success(order) => order
        case Result.Failure(aggErr) =>
          val errorMsgs =
            aggErr
              .errors
              .map(err => s"Field validation failed for value '${err.inputValue}': ${err.message}")
          Abort.fail(DirectValidationError(errorMsgs)).now
        case Result.Panic(t) =>
          Abort.panic(t).now

  // Step 2: Blacklist Check
  def checkBlockedUser(phone: DirectPhone): Unit < Abort[DirectOrderError] =
    if blockedPhones.contains(phone) then Abort.fail(DirectBlockedUser(phone))
    else Kyo.unit

  // Step 3: Stock Verification
  def checkStock(sku: DirectProductSku, qty: DirectQuantity): Unit < Abort[DirectOrderError] =
    val available = stockDb.getOrElse(sku, 0)
    if available < qty then Abort.fail(DirectOutOfStock(sku, qty, available))
    else Kyo.unit

  // Step 4: Payment Processing
  def processPayment(price: DirectPrice, qty: DirectQuantity): Unit < Abort[DirectOrderError] =
    val totalAmount = price * qty
    if totalAmount > 1000.0 then
      Abort.fail(
        DirectPaymentFailed(totalAmount, "Exceeded maximum single transaction limit ($1000.0)")
      )
    else Kyo.unit

  // Step 5: Full Order Processing Orchestration (using now instead of for-yield in kyo-direct)
  def processOrder(
    rawId: String,
    rawSku: String,
    rawQty: Int,
    rawPrice: Double,
    rawPhone: String,
  ): DirectValidatedOrder < (Abort[DirectOrderError] & Sync) =
    direct:
      // Sequential execution using .now
      val order = validate(rawId, rawSku, rawQty, rawPrice, rawPhone).now
      checkBlockedUser(order.phone).now
      checkStock(order.sku, order.quantity).now
      processPayment(order.price, order.quantity).now
      Console.printLine {
        s"[Direct Order Processing] Order ${order.id} processed successfully! SKU: ${order.sku}, Qty: ${order.quantity}, Total: $$${order.price * order.quantity}"
      }.now
      order

// -----------------------------------------------------------------------------
// 4. Main Application (Main Application)
// -----------------------------------------------------------------------------

object KyoDirectOrderProcessingExampleMain extends KyoApp:
  private def runScenario(
    name: String,
    rawId: String,
    rawSku: String,
    rawQty: Int,
    rawPrice: Double,
    rawPhone: String,
  ): Unit < Sync =
    direct {
      Console.printLine(s"\n--- Scenario (Direct): $name ---").now
      val result =
        Abort
          .run(
            DirectOrderService.processOrder(rawId, rawSku, rawQty, rawPrice, rawPhone)
          )
          .now
      Console.printLine(s"Result: $result").now
    }

  run {
    direct {
      Console.printLine("=========================================================").now
      Console.printLine("   IronKyo Direct Style Order Processing Demo            ").now
      Console.printLine("=========================================================").now

      // Scenario 1: Normal order receipt and processing
      runScenario(
        name = "Valid Order Processing",
        rawId = "ORD-20260609-A1B2",
        rawSku = "APP-10023",
        rawQty = 5,
        rawPrice = 99.0,
        rawPhone = "010-9999-9999",
      ).now

      // Scenario 2: Invalid inputs (error accumulation check)
      runScenario(
        name = "Invalid Inputs (Error Accumulation)",
        rawId = "INVALID_ID_FORMAT",
        rawSku = "invalid-sku",
        rawQty = -5,
        rawPrice = -10.0,
        rawPhone = "010-123-456",
      ).now

      // Scenario 3: Order attempt by blacklisted customer
      runScenario(
        name = "Blocked Customer Order",
        rawId = "ORD-20260609-C3D4",
        rawSku = "APP-10023",
        rawQty = 2,
        rawPrice = 120.0,
        rawPhone = "010-0000-0000", // Blacklisted number
      ).now

      // Scenario 4: Out of stock order attempt
      runScenario(
        name = "Out Of Stock Order",
        rawId = "ORD-20260609-E5F6",
        rawSku = "CLO-55443", // Product with zero stock
        rawQty = 1,
        rawPrice = 25.0,
        rawPhone = "010-7777-7777",
      ).now

      // Scenario 5: Single transaction limit exceeded
      runScenario(
        name = "Payment Limit Exceeded",
        rawId = "ORD-20260609-G7H8",
        rawSku = "APP-10023",
        rawQty = 20,
        rawPrice = 90.0, // 20 * $90 = $1800 (exceeds $1000 limit)
        rawPhone = "010-7777-7777",
      ).now

      Console.printLine("\n=========================================================").now
    }
  }
