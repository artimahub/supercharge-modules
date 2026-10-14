// Exercises Generate's real arg parsing (Generate.parseArgs is non-private for
// this). No nested agent is spawned. Run with: sbt "runMain ArgCheck"
object ArgCheck:
  private var failures = 0

  private def check(label: String, actual: Any, expected: Any): Unit =
    if actual == expected then println(s"  ok   $label -> $actual")
    else
      failures += 1
      println(s"  FAIL $label -> got $actual, want $expected")

  def main(args: Array[String]): Unit =
    val parse = Generate.parseArgs(_)

    println("parseArgs")
    // The reported bug: "verify" must never be taken as the output directory.
    check("verify", parse(List("verify")), (Nil, Set("verify")))
    check("dir verify", parse(List("../out", "verify")), (List("../out"), Set("verify")))
    check("--verify", parse(List("--verify")), (Nil, Set("verify")))
    check("dir --verify", parse(List("dir", "--verify")), (List("dir"), Set("verify")))
    check("no args", parse(Nil), (Nil, Set.empty[String]))
    check("dir only", parse(List("dir")), (List("dir"), Set.empty[String]))
    check("verify first", parse(List("verify", "dir")), (List("dir"), Set("verify")))
    check(
      "dir ending in 'verify' is still a directory",
      parse(List("/tmp/verify")),
      (List("/tmp/verify"), Set.empty[String])
    )
    check(
      "flag after flags",
      parse(List("a", "--verify", "--other")),
      (List("a"), Set("verify", "other"))
    )

    println("resolved target directory")
    def target(positionals: List[String]): String =
      positionals.headOption.getOrElse("../roman-generated")
    check("bare verify -> default target", target(parse(List("verify"))._1), "../roman-generated")
    check("verify + dir", target(parse(List("verify", "out"))._1), "out")
    check("dir + verify", target(parse(List("out", "verify"))._1), "out")

    println("misuse detection")
    def positionalsOf(xs: List[String]): List[String] = parse(xs)._1
    check("one positional accepted", positionalsOf(List("dir")).size, 1)
    check("verify is not positional", positionalsOf(List("verify", "verify")).size, 0)
    check("two dirs rejected", positionalsOf(List("a", "b")).size, 2)

    println(if failures == 0 then "ALL ARG CHECKS PASSED" else s"$failures FAILURES")
    if failures != 0 then sys.exit(1)
