package com.teamgehem.kyo_test

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.string.*
import kyo.*

import com.teamgehem.ironkyo.*

// ─────────────────────────────────────────────────────────────────────────────
// 1. 도메인 제약 조건 및 타입 정의 (Domain Types with Iron Constraints)
// ─────────────────────────────────────────────────────────────────────────────

// 주문 ID: ORD-YYYYMMDD-XXXX (예: ORD-20260609-A1B2)
type OrderId = String :| Match["^ORD-\\d{8}-[A-Z0-9]{4}$"]

// 상품 SKU: ABC-12345 형식 (예: APP-10023, ELE-99882)
type ProductSku = String :| Match["^[A-Z]{3}-\\d{5}$"]

// 주문 수량: 1 ~ 100개
type Quantity = Int :| (Positive & LessEqual[100])

// 상품 단가: 양수
type Price = Double :| Positive

// 연락처: 010-XXXX-XXXX 형식
type Phone = String :| Match["^010-\\d{4}-\\d{4}$"]

// 유효한 주문 도메인 모델
final case class ValidatedOrder(
  id: OrderId,
  sku: ProductSku,
  quantity: Quantity,
  price: Price,
  phone: Phone,
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. 비즈니스 에러 정의 (Business Errors)
// ─────────────────────────────────────────────────────────────────────────────

sealed trait OrderError
final case class ValidationError(errors: List[String]) extends OrderError
final case class OutOfStock(
  sku: String,
  requested: Int,
  available: Int,
) extends OrderError
final case class BlockedUser(phone: String) extends OrderError
final case class PaymentFailed(amount: Double, reason: String) extends OrderError

// ─────────────────────────────────────────────────────────────────────────────
// 3. 비즈니스 서비스 구현 (Business Services)
// ─────────────────────────────────────────────────────────────────────────────

object OrderService:

  // 가상의 데이터베이스 상태 (재고 현황)
  private val stockDb =
    Map(
      "APP-10023" -> 50, // Apple
      "ELE-99882" -> 5, // Electronics
      "CLO-55443" -> 0, // Clothing
    )

  // 가상의 블랙리스트 전화번호
  private val blockedPhones =
    Set(
      "010-0000-0000",
      "010-1234-5678",
    )

  // 1단계: 입력값 검증 (Error Accumulation using validateAll)
  @scala.annotation.nowarn("msg=pattern selector")
  def validate(
    rawId: String,
    rawSku: String,
    rawQty: Int,
    rawPrice: Double,
    rawPhone: String,
  ): ValidatedOrder < Abort[OrderError] =
    Abort
      .run[AggregatedConstraintError]:
        validateAll(
          field(rawId).as[Match["^ORD-\\d{8}-[A-Z0-9]{4}$"]],
          field(rawSku).as[Match["^[A-Z]{3}-\\d{5}$"]],
          field(rawQty).as[Positive & LessEqual[100]],
          field(rawPrice).as[Positive],
          field(rawPhone).as[Match["^010-\\d{4}-\\d{4}$"]],
        ).into[ValidatedOrder]
      .map: res =>
        res match
          case Result.Success(order) => order
          case Result.Failure(aggErr) =>
            val errorMsgs =
              aggErr
                .errors
                .map(err =>
                  s"Field validation failed for value '${err.inputValue}': ${err.message}"
                )
            Abort.fail(ValidationError(errorMsgs))
          case Result.Panic(t) =>
            Abort.panic(t)

  // 2단계: 블랙리스트 체크
  def checkBlockedUser(phone: Phone): Unit < Abort[OrderError] =
    if blockedPhones.contains(phone) then Abort.fail(BlockedUser(phone))
    else Kyo.unit

  // 3단계: 재고 검증
  def checkStock(sku: ProductSku, qty: Quantity): Unit < Abort[OrderError] =
    val available = stockDb.getOrElse(sku, 0)
    if available < qty then Abort.fail(OutOfStock(sku, qty, available))
    else Kyo.unit

  // 4단계: 결제 처리 (총액이 $1000 초과시 실패 처리)
  def processPayment(price: Price, qty: Quantity): Unit < Abort[OrderError] =
    val totalAmount = price * qty
    if totalAmount > 1000.0 then
      Abort.fail(PaymentFailed(totalAmount, "Exceeded maximum single transaction limit ($1000.0)"))
    else Kyo.unit

  // 5단계: 전체 주문 처리 오케스트레이션 (Orchestration)
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def processOrder(
    rawId: String,
    rawSku: String,
    rawQty: Int,
    rawPrice: Double,
    rawPhone: String,
  ): ValidatedOrder < (Abort[OrderError] & Sync) =
    for
      // 1. 입력값 검증 (ValidationError 발생 가능)
      order: ValidatedOrder <- validate(rawId, rawSku, rawQty, rawPrice, rawPhone)
      // 2. 고객 블랙리스트 검증 (BlockedUser 발생 가능)
      _ <- checkBlockedUser(order.phone)
      // 3. 상품 재고 검증 (OutOfStock 발생 가능)
      _ <- checkStock(order.sku, order.quantity)
      // 4. 결제 처리 (PaymentFailed 발생 가능)
      _ <- processPayment(order.price, order.quantity)
      // 5. 성공 로그 출력
      _ <-
        Console.printLine {
          s"⚡ [Order Processing] Order ${order.id} processed successfully! SKU: ${order.sku}, Qty: ${order.quantity}, Total: $$${order.price * order.quantity}"
        }
    yield order

// ─────────────────────────────────────────────────────────────────────────────
// 4. 메인 실행 애플리케이션 (Main Application)
// ─────────────────────────────────────────────────────────────────────────────

object IronKyoOrderProcessingExampleMain extends KyoApp:
  private def runScenario(
    name: String,
    rawId: String,
    rawSku: String,
    rawQty: Int,
    rawPrice: Double,
    rawPhone: String,
  ): Unit < Sync =
    for
      _ <- Console.printLine(s"\n--- Scenario: $name ---")
      result <-
        Abort.run(
          OrderService.processOrder(rawId, rawSku, rawQty, rawPrice, rawPhone)
        )
      _ <- Console.printLine(s"Result: $result")
    yield ()

  run {
    for
      _ <- Console.printLine("=========================================================")
      _ <- Console.printLine("   IronKyo Practical E-Commerce Order Processing Demo   ")
      _ <- Console.printLine("=========================================================")

      // 시나리오 1: 정상적인 주문 접수 및 처리
      _ <-
        runScenario(
          name = "Valid Order Processing",
          rawId = "ORD-20260609-A1B2",
          rawSku = "APP-10023",
          rawQty = 5,
          rawPrice = 99.0,
          rawPhone = "010-9999-9999",
        )

      // 시나리오 2: 입력값 제약조건 위반 (복수 오류 수집 확인)
      _ <-
        runScenario(
          name = "Invalid Inputs (Error Accumulation)",
          rawId = "INVALID_ID_FORMAT",
          rawSku = "invalid-sku",
          rawQty = -5, // 양수여야 함
          rawPrice = -10.0, // 양수여야 함
          rawPhone = "010-123-456", // 포맷 불일치
        )

      // 시나리오 3: 블랙리스트 대상 고객의 주문 시도
      _ <-
        runScenario(
          name = "Blocked Customer Order",
          rawId = "ORD-20260609-C3D4",
          rawSku = "APP-10023",
          rawQty = 2,
          rawPrice = 120.0,
          rawPhone = "010-0000-0000", // 블랙리스트 번호
        )

      // 시나리오 4: 재고 부족 상품 주문 시도
      _ <-
        runScenario(
          name = "Out Of Stock Order",
          rawId = "ORD-20260609-E5F6",
          rawSku = "CLO-55443", // 재고가 0인 상품
          rawQty = 1,
          rawPrice = 25.0,
          rawPhone = "010-7777-7777",
        )

      // 시나리오 5: 단일 거래 결제 한도 초과
      _ <-
        runScenario(
          name = "Payment Limit Exceeded",
          rawId = "ORD-20260609-G7H8",
          rawSku = "APP-10023",
          rawQty = 20,
          rawPrice = 90.0, // 20 * $90 = $1800 (한도 $1000 초과)
          rawPhone = "010-7777-7777",
        )

      _ <- Console.printLine("\n=========================================================")
    yield ()
  }
