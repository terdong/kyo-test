package com.teamgehem.kyo_test

import scala.annotation.nowarn

import kyo.*

@nowarn("msg=unused")
object KyoDirectNowLaterExampleMain extends KyoApp:

  // 효과가 담긴 가상의 작업들
  val fetchConfig: String < Sync =
    Console.printLine("⚙️ 설정 정보를 가져옵니다...").map[String, Sync](_ => "DB_URL=jdbc:mysql://...")

  val fetchUserData: String < Sync =
    Console.printLine("👤 유저 데이터를 DB에서 조회합니다...").map[String, Sync](_ => "User: Darren")

  run {
    direct {
      Console.printLine("=========================================================").now

      // ─────────────────────────────────────────────────────────────────────────────
      // [기본 개념] .now vs .later
      // ─────────────────────────────────────────────────────────────────────────────

      // 1. .now 사용: 설정을 가져오는 즉시 결과를 풀어서 바인딩합니다. (String 타입)
      val config: String = fetchConfig.now
      Console.printLine(s"-> 즉시 처리된 설정: $config").now

      // 2. .later 사용: 유저 데이터를 조회하는 작업을 지금 실행하지 않고,
      // '이펙트 그 자체(String < Sync)' 형태로 변수에 보관합니다.
      // 만약 .later 없이 `val pendingJob = fetchUserData` 라고 쓰면,
      // direct 매크로가 자동으로 언랩하여 String 데이터로 바인딩을 시도하고 즉시 실행해 버립니다.
      val pendingJob: String < Sync = fetchUserData.later

      Console.printLine("-> 유저 데이터 조회 작업이 변수에 저장되었습니다. (아직 실행 안 됨)").now

      // 다른 비즈니스 로직 수행...
      Console.printLine("-> 다른 비즈니스 로직을 수행 중...").now
      Async.sleep(1.second).now

      // 나중에 필요해진 시점에 보관해두었던 이펙트(pendingJob)를 .now로 꺼내어 실행합니다.
      Console.printLine("-> 이제 유저 데이터가 필요하므로 보관해둔 작업을 실행합니다.").now
      val userData: String = pendingJob.now
      Console.printLine(s"-> 가져온 유저 데이터: $userData").now

      Console.printLine("---------------------------------------------------------").now

      // ─────────────────────────────────────────────────────────────────────────────
      // [구체적 예시 1] 조건부 실행 (Conditional Execution)에서의 .later 사용 이유
      // ─────────────────────────────────────────────────────────────────────────────
      // 특정 조건이 참일 때만 이펙트를 실행해야 하는 경우, 미리 정의된 이펙트 값을 lazy하게 전달할 수 있습니다.
      val cacheMiss = true
      val dbFetchJob: String < Sync = fetchUserData.later // 이펙트를 값으로서 준비만 해둠

      Console.printLine("-> 조건부 실행 테스트를 시작합니다...").now
      val finalUser: String =
        if cacheMiss then
          Console.printLine("   [조건 참] 캐시에 없으므로 dbFetchJob을 실행합니다.").now
          dbFetchJob.now // 조건이 참일 때만 실제 이펙트 실행
        else "CachedUser"
      Console.printLine(s"-> 조건부 실행 결과: $finalUser").now

      Console.printLine("---------------------------------------------------------").now

      // ─────────────────────────────────────────────────────────────────────────────
      // [구체적 예시 2] pendingJob 중첩(Nesting) 및 재사용(Reusing) 예제
      // ─────────────────────────────────────────────────────────────────────────────
      // 이미 선언된 pendingJob들을 값으로서 넘겨받거나, 중첩해서 새로운 지연 연산을 설계할 수 있습니다.

      val userJob: String < Sync = fetchUserData.later
      val configJob: String < Sync = fetchConfig.later

      // [방법 A] nested direct + .later 스타일
      // direct 블록 안에서 두 작업을 꺼내어(unwrap) 조합한 새로운 '지연 작업'을 만듭니다.
      // 외부 direct 블록에서 이 조합된 연산 자체가 즉시 실행되지 않도록,
      // 내부 direct 블록 끝에 `.later`를 붙여 이펙트(String < Sync) 상태로 보관합니다.
      val combinedPendingJobA: String < Sync =
        direct {
          val user = userJob.now // userJob의 이펙트 실행 시점을 이 combinedJob 실행 시점으로 지연
          val cfg = configJob.now // configJob의 이펙트 실행 시점을 이 combinedJob 실행 시점으로 지연
          s"CombinedResultA(User = $user, Config = $cfg)"
        }.later

      // [방법 B] for-comprehension 스타일
      // Kyo의 이펙트(A < S)는 map과 flatMap을 지원하므로, 표준 Scala의 for-comprehension을
      // 그대로 사용할 수도 있습니다. 이 방식은 direct 매크로가 바인딩 처리를 시도하지 않기 때문에,
      // 별도로 `.later`를 붙이지 않아도 자연스럽게 지연 실행(lazy) 상태로 보관됩니다.
      val combinedPendingJobB: String < Sync =
        (for
          user <- userJob
          cfg <- configJob
        yield s"CombinedResultB(User = $user, Config = $cfg)").later

      Console.printLine("-> 두 가지 방식으로 combinedPendingJob이 생성되었습니다 (아직 내부 연산 실행 안 됨)").now

      // 두 작업 모두 .now 호출 시점에 동일하게 실행됩니다.
      Console.printLine("-> [방법 A 실행] combinedPendingJobA를 실행합니다:").now
      val resultA: String = combinedPendingJobA.now
      Console.printLine(s"   결과 A: $resultA").now

      Console.printLine("-> [방법 B 실행] combinedPendingJobB를 실행합니다:").now
      val resultB: String = combinedPendingJobB.now
      Console.printLine(s"   결과 B: $resultB").now

      Console.printLine("-> [재사용 실행] 동일한 combinedPendingJobB를 다시 실행합니다:").now
      val result2: String = combinedPendingJobB.now
      Console.printLine(s"   결과 2: $result2").now

      Console.printLine("=========================================================").now
    }
  }
