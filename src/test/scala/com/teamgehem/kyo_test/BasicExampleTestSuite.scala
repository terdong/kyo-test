package com.teamgehem
package kyo_test

final class BasicExampleTestSuite extends TestSuite:
  test("hello world"):
    forAll: (int: Int, string: String) =>
      expectEquals(int, int)
      expectEquals(string, string)
