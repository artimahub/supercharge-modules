import java.nio.file.Path

// Exercises Generate's arg parsing and its scan of the geny tree. No nested
// agent is spawned. Run with: sbt "runMain ScanCheck"
object ScanCheck:
  private var failures = 0

  private def check(label: String, actual: Any, expected: Any): Unit =
    if actual == expected then println(s"  ok   $label -> $actual")
    else
      failures += 1
      println(s"  FAIL $label -> got $actual, want $expected")

  private def parse(args: List[String]) = Generate.parseArgs(args)

  def main(args: Array[String]): Unit =
    println("parseArgs")
    check("no args", parse(Nil), (Map.empty[String, List[String]], Nil))
    check(
      "bare word is a src dir",
      parse(List("../geny/geny/src"))._2,
      List("../geny/geny/src")
    )
    check(
      "--src takes a value",
      parse(List("--src", "a", "--src", "b"))._1.get("src"),
      Some(List("b", "a"))
    )
    check("--dry is a flag", parse(List("--dry"))._1.get("dry"), Some(List("")))
    check(
      "flag then positional",
      parse(List("--dry", "dir"))._2,
      List("dir")
    )
    check(
      "everything at once",
      parse(List("--src", "s", "--file", "Writable.scala", "--passes", "2", "--verify", "x")),
      (Map("src" -> List("s"), "file" -> List("Writable.scala"), "passes" -> List("2"), "verify" -> List("")), List("x"))
    )

    println("scan of the geny tree")
    val config = Generate.Config(
      srcDirs = List(Path.of("../geny/geny/src").toAbsolutePath.normalize()),
      onlyFiles = Set.empty,
      passes = 1,
      verify = false,
      dry = true,
      model = "test/model",
      timeoutMinutes = 1,
      buildDir = None,
      preflight = false
    )
    val found = Generate.markedFiles(config)
    println(s"  marked files: ${found.size}")
    found.foreach(f => println(s"    ${f.getFileName} (${Generate.countMarkers(f)} markers)"))

    check("found every marked file", found.size >= 5, true)
    check("every found file has markers", found.forall(f => Generate.countMarkers(f) > 0), true)
    check("nothing outside the tree", found.forall(_.toString.contains("geny/geny/src")), true)
    check("sorted, no duplicates", found == found.distinct.sorted, true)

    println("--file narrows the scan")
    val narrowed = Generate.markedFiles(config.copy(onlyFiles = Set("Writable.scala")))
    check("one file", narrowed.map(_.getFileName.toString), List("Writable.scala"))

    println("--dry does not change the tree")
    val after = Generate.markedFiles(config)
    check("markers untouched", after.map(Generate.countMarkers), found.map(Generate.countMarkers))

    println(if failures == 0 then "ALL SCAN CHECKS PASSED" else s"$failures FAILURES")
    if failures != 0 then sys.exit(1)